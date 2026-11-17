package com.programmers.chzzk.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.programmers.chzzk.TestMembers;
import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.broadcast.BroadcastRepository;
import com.programmers.chzzk.broadcast.LiveStatus;
import com.programmers.chzzk.member.MemberRepository;

/**
 * 송출 휴대폰의 Wi-Fi가 끊기면 WebSocket 연결은 서버에 남은 채 생존 신호(heartbeat)만 멈춘다.
 * 이때 생존 신호 만료로 방송을 끝내면 시청 화면에도 종료가 전달되고 연결이 정리되어야 한다(실기기 검증 #11).
 * 만료 점검은 자동 실행(1시간 주기)을 기다리지 않고 스케줄러를 직접 호출해 확인한다. timeout은 1초로 줄였다.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"app.live.expiry-check-enabled=true",
		"app.live.expiry-check-interval=1h",
		"app.live.heartbeat-timeout=1s"
	})
@ActiveProfiles("test")
class LiveBroadcastExpiryServerTests {

	private static final long PAST_TIMEOUT_MILLIS = 1_300;

	@LocalServerPort
	private int port;

	@Autowired
	private LiveBroadcastExpiryScheduler expiryScheduler;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private String sessionCookie;

	@Autowired
	private LiveSessionRegistry registry;

	private final List<SignalingTestClient> clients = new ArrayList<>();

	@BeforeEach
	void clearBroadcasts() throws Exception {
		broadcastRepository.deleteAll();
		TestMembers.saveWithPassword(memberRepository, passwordEncoder, "live_owner");
		sessionCookie = SignalingTestClient.login(port, "live_owner", TestMembers.PASSWORD);
	}

	@AfterEach
	void closeClients() throws Exception {
		for (SignalingTestClient client : clients) {
			client.close();
		}
	}

	@Test
	void heartbeatExpiryEndsBroadcastNotifiesViewerAndClosesConnections() throws Exception {
		SignalingTestClient publisher = connect();
		long broadcastId = start(publisher);
		SignalingTestClient viewer = connect();
		watch(viewer, publisher, broadcastId);
		Thread.sleep(PAST_TIMEOUT_MILLIS);

		expiryScheduler.endExpiredBroadcasts();

		Broadcast ended = broadcastRepository.findById(broadcastId).orElseThrow();
		assertThat(ended.getLiveStatus()).isEqualTo(LiveStatus.ENDED);
		assertThat(ended.getEndedAt()).isNotNull();
		viewer.next("ended");
		viewer.awaitClosed();
		publisher.next("ended");
		publisher.awaitClosed();
		assertThat(registry.publisherOf(broadcastId)).isEmpty();
	}

	@Test
	void repeatedExpiryCheckDoesNotNotifyAgain() throws Exception {
		SignalingTestClient publisher = connect();
		long broadcastId = start(publisher);
		SignalingTestClient viewer = connect();
		watch(viewer, publisher, broadcastId);
		Thread.sleep(PAST_TIMEOUT_MILLIS);
		expiryScheduler.endExpiredBroadcasts();
		viewer.next("ended");
		publisher.next("ended");

		expiryScheduler.endExpiredBroadcasts();

		viewer.expectNothing();
		publisher.expectNothing();
		assertThat(broadcastRepository.findById(broadcastId).orElseThrow().getLiveStatus())
			.isEqualTo(LiveStatus.ENDED);
	}

	/**
	 * 만료 점검과 송출자의 종료 요청이 겹쳐도 시청자·송출자는 종료 알림을 정확히 한 번 받는다.
	 * 어느 쪽이 먼저 처리되든 성립해야 하는 결과만 확인한다.
	 */
	@Test
	void expiryRacingWithEndRequestNotifiesExactlyOnce() throws Exception {
		SignalingTestClient publisher = connect();
		long broadcastId = start(publisher);
		SignalingTestClient viewer = connect();
		watch(viewer, publisher, broadcastId);
		Thread.sleep(PAST_TIMEOUT_MILLIS);

		CountDownLatch go = new CountDownLatch(1);
		CompletableFuture<Void> expiry = CompletableFuture.runAsync(() -> {
			await(go);
			expiryScheduler.endExpiredBroadcasts();
		});
		CompletableFuture<Void> endRequest = CompletableFuture.runAsync(() -> {
			await(go);
			sendIgnoringClosed(publisher, "{\"type\":\"end\"}");
		});
		go.countDown();
		CompletableFuture.allOf(expiry, endRequest).get(5, TimeUnit.SECONDS);

		viewer.next("ended");
		viewer.awaitClosed();
		viewer.expectNothing();
		publisher.next("ended");
		publisher.awaitClosed();
		publisher.expectNothing();
		assertThat(broadcastRepository.findById(broadcastId).orElseThrow().getLiveStatus())
			.isEqualTo(LiveStatus.ENDED);
		assertThat(registry.publisherOf(broadcastId)).isEmpty();
	}

	private SignalingTestClient connect() throws Exception {
		SignalingTestClient client = SignalingTestClient.connect(port, sessionCookie);
		clients.add(client);
		return client;
	}

	private static long start(SignalingTestClient publisher) throws Exception {
		publisher.send("{\"type\":\"start\",\"title\":\"Wi-Fi 끊김\"}");
		return publisher.next("started").path("broadcastId").asLong();
	}

	private static void watch(SignalingTestClient viewer, SignalingTestClient publisher, long broadcastId)
		throws Exception {
		viewer.send("{\"type\":\"watch\",\"broadcastId\":" + broadcastId + "}");
		viewer.next("watching");
		publisher.next("viewer-joined");
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 만료 처리가 먼저 연결을 닫았다면 종료 요청은 보낼 수 없다. 그 경우도 정상 경쟁 결과다. */
	private static void sendIgnoringClosed(SignalingTestClient client, String json) {
		try {
			if (client.isOpen()) {
				client.send(json);
			}
		} catch (Exception closedByServer) {
			// 서버가 이미 연결을 닫음
		}
	}

}
