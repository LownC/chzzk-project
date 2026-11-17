package com.programmers.chzzk.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.CloseStatus;

import com.programmers.chzzk.TestMembers;
import com.programmers.chzzk.broadcast.BroadcastRepository;
import com.programmers.chzzk.broadcast.LiveStatus;
import com.programmers.chzzk.member.MemberRepository;

import tools.jackson.databind.JsonNode;

/**
 * (⑤) 인스턴스가 줄어 송출 연결이 있던 서버가 내려가도 방송이 끝나지 않고,
 * 방송 소유자의 송출 화면이 다른 연결로 {@code resume}해 시청자를 그대로 이어 받는지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LiveSignalingResumeServerTests {

	private static final String SDP = "v=0\r\no=- 46117317 2 IN IP4 127.0.0.1\r\ns=-\r\n";

	@LocalServerPort
	private int port;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private LiveSignalingHandler handler;

	@Autowired
	private LiveRoomStore registry;

	@Autowired
	private ApplicationContext applicationContext;

	private String ownerCookie;

	private final List<SignalingTestClient> clients = new ArrayList<>();

	@BeforeEach
	void setUp() throws Exception {
		broadcastRepository.deleteAll();
		TestMembers.saveWithPassword(memberRepository, passwordEncoder, "resume_owner");
		ownerCookie = SignalingTestClient.login(port, "resume_owner", TestMembers.PASSWORD);
	}

	@AfterEach
	void closeClients() throws Exception {
		for (SignalingTestClient client : clients) {
			client.close();
		}
	}

	@Test
	void ownerResumesOnNewConnectionAndKeepsViewers() throws Exception {
		SignalingTestClient publisher = connect(ownerCookie);
		long broadcastId = start(publisher);
		SignalingTestClient viewer = connect(null);
		viewer.send("{\"type\":\"watch\",\"broadcastId\":" + broadcastId + "}");
		viewer.next("watching");
		String viewerId = publisher.next("viewer-joined").path("viewerId").asString();

		SignalingTestClient resumed = connect(ownerCookie);
		resumed.send("{\"type\":\"resume\",\"broadcastId\":" + broadcastId + "}");

		JsonNode resumedMessage = resumed.next("resumed");
		assertThat(resumedMessage.path("broadcastId").asLong()).isEqualTo(broadcastId);
		// 다시 연결한 송출 화면은 이 목록으로 남아 있는 시청자와 영상 연결을 맞춘다.
		assertThat(resumedMessage.path("viewerIds")).hasSize(1);
		assertThat(resumedMessage.path("viewerIds").get(0).asString()).isEqualTo(viewerId);
		viewer.send("{\"type\":\"answer\",\"sdp\":\"" + SDP.replace("\r\n", "\\r\\n") + "\"}");
		JsonNode answer = resumed.next("answer");
		assertThat(answer.path("viewerId").asString()).isEqualTo(viewerId);

		// 예전 송출 연결이 늦게 끊겨도, 이미 송출자가 바뀌었으므로 방송을 끝내지 않는다.
		publisher.close();
		publisher.awaitClosed();
		viewer.expectNothing();
		assertThat(broadcastRepository.findById(broadcastId).orElseThrow().getLiveStatus()).isEqualTo(LiveStatus.LIVE);
	}

	@Test
	void resumeRequiresLoggedInOwnerOfLiveBroadcast() throws Exception {
		SignalingTestClient publisher = connect(ownerCookie);
		long broadcastId = start(publisher);

		SignalingTestClient anonymous = connect(null);
		anonymous.send("{\"type\":\"resume\",\"broadcastId\":" + broadcastId + "}");
		anonymous.expectError("UNAUTHENTICATED");

		TestMembers.saveWithPassword(memberRepository, passwordEncoder, "resume_other");
		SignalingTestClient other = connect(SignalingTestClient.login(port, "resume_other", TestMembers.PASSWORD));
		other.send("{\"type\":\"resume\",\"broadcastId\":" + broadcastId + "}");
		other.expectError("INVALID_ROLE");

		SignalingTestClient missing = connect(ownerCookie);
		missing.send("{\"type\":\"resume\",\"broadcastId\":" + (broadcastId + 1000) + "}");
		missing.expectError("NOT_FOUND");

		publisher.send("{\"type\":\"end\"}");
		publisher.awaitClosed();
		SignalingTestClient afterEnd = connect(ownerCookie);
		afterEnd.send("{\"type\":\"resume\",\"broadcastId\":" + broadcastId + "}");
		afterEnd.expectError("NOT_LIVE");
		assertThat(registry.publisherOf(broadcastId)).isEmpty();
	}

	@Test
	@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
	void shuttingDownInstanceAsksClientsToReconnectAndKeepsBroadcastLive() throws Exception {
		SignalingTestClient publisher = connect(ownerCookie);
		long broadcastId = start(publisher);
		SignalingTestClient viewer = connect(null);
		viewer.send("{\"type\":\"watch\",\"broadcastId\":" + broadcastId + "}");
		viewer.next("watching");
		publisher.next("viewer-joined");

		handler.onApplicationEvent(new ContextClosedEvent(applicationContext));

		// 화면은 이 "reconnect" 메시지를 받으면 다시 연결한다. 닫는 코드(1012)는 보조 신호라
		// 테스트용 Tomcat 클라이언트가 보고하는 코드는 따지지 않고, 연결이 닫히는지만 확인한다.
		// 시청자 연결이 먼저 닫히면 송출자는 reconnect보다 viewer-left를 먼저 받을 수 있다.
		publisher.nextSkipping("reconnect");
		viewer.next("reconnect");
		assertThat(publisher.awaitClosed().getCode()).isNotEqualTo(CloseStatus.NORMAL.getCode());
		assertThat(viewer.awaitClosed().getCode()).isNotEqualTo(CloseStatus.NORMAL.getCode());
		assertThat(broadcastRepository.findById(broadcastId).orElseThrow().getLiveStatus()).isEqualTo(LiveStatus.LIVE);
		assertThat(registry.publisherOf(broadcastId)).isPresent();
	}

	private SignalingTestClient connect(String cookie) throws Exception {
		SignalingTestClient client = cookie == null
			? SignalingTestClient.connect(port)
			: SignalingTestClient.connect(port, cookie);
		clients.add(client);
		return client;
	}

	private static long start(SignalingTestClient publisher) throws Exception {
		publisher.send("{\"type\":\"start\",\"title\":\"이어지는 라이브\"}");
		return publisher.next("started").path("broadcastId").asLong();
	}

}
