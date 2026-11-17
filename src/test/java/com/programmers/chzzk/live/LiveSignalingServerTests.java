package com.programmers.chzzk.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.broadcast.BroadcastRepository;
import com.programmers.chzzk.broadcast.LiveStatus;

import tools.jackson.databind.JsonNode;

/**
 * 실제 서버의 /ws/live에 WebSocket 클라이언트로 붙어 시그널링 규칙을 확인한다.
 * 실제 카메라나 WebRTC 미디어 연결은 이 테스트의 범위가 아니다(SDP·ICE는 문자열로만 오간다).
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = "app.live.max-viewers=2")
@ActiveProfiles("test")
class LiveSignalingServerTests {

	private static final String SDP = "v=0\r\no=- 46117317 2 IN IP4 127.0.0.1\r\ns=-\r\n";
	private static final String CANDIDATE =
		"{\"candidate\":\"candidate:1 1 udp 2122260223 192.168.0.10 54321 typ host\",\"sdpMid\":\"0\",\"sdpMLineIndex\":0}";

	@LocalServerPort
	private int port;

	@Autowired
	private BroadcastRepository broadcastRepository;

	private final java.util.List<SignalingTestClient> clients = new java.util.ArrayList<>();

	@BeforeEach
	void clearBroadcasts() {
		broadcastRepository.deleteAll();
	}

	@AfterEach
	void closeClients() throws Exception {
		for (SignalingTestClient client : clients) {
			client.close();
		}
	}

	@Test
	void startCreatesLiveBroadcastAndReturnsWatchUrl() throws Exception {
		SignalingTestClient publisher = connect();

		publisher.send("{\"type\":\"start\",\"title\":\"  휴대폰 라이브  \"}");

		JsonNode started = publisher.next("started");
		long broadcastId = started.path("broadcastId").asLong();
		assertThat(started.path("watchUrl").asString()).isEqualTo("/broadcasts/" + broadcastId);
		Broadcast saved = broadcastRepository.findById(broadcastId).orElseThrow();
		assertThat(saved.getTitle()).isEqualTo("휴대폰 라이브");
		assertThat(saved.getLiveStatus()).isEqualTo(LiveStatus.LIVE);
	}

	@Test
	void startWithInvalidTitleIsRejectedWithoutSaving() throws Exception {
		SignalingTestClient publisher = connect();

		publisher.send("{\"type\":\"start\",\"title\":\"   \"}");
		publisher.expectError("INVALID_TITLE");
		publisher.send("{\"type\":\"start\"}");
		publisher.expectError("INVALID_TITLE");

		assertThat(broadcastRepository.count()).isZero();
	}

	@Test
	void watchNotifiesPublisherWithServerIssuedViewerId() throws Exception {
		SignalingTestClient publisher = connect();
		long broadcastId = start(publisher, "라이브");
		SignalingTestClient viewer = connect();

		viewer.send("{\"type\":\"watch\",\"broadcastId\":" + broadcastId + "}");

		assertThat(viewer.next("watching").path("broadcastId").asLong()).isEqualTo(broadcastId);
		assertThat(publisher.next("viewer-joined").path("viewerId").asString()).isNotBlank();
	}

	@Test
	void offerAnswerAndIceAreRelayedBetweenPublisherAndViewer() throws Exception {
		SignalingTestClient publisher = connect();
		long broadcastId = start(publisher, "라이브");
		SignalingTestClient viewer = connect();
		String viewerId = watch(viewer, publisher, broadcastId);

		publisher.send("{\"type\":\"offer\",\"viewerId\":\"" + viewerId + "\",\"sdp\":" + quote(SDP) + "}");
		JsonNode offer = viewer.next("offer");
		assertThat(offer.path("sdp").asString()).isEqualTo(SDP);
		assertThat(offer.has("viewerId")).isFalse();

		viewer.send("{\"type\":\"answer\",\"viewerId\":\"forged\",\"broadcastId\":999,\"sdp\":" + quote(SDP) + "}");
		JsonNode answer = publisher.next("answer");
		assertThat(answer.path("viewerId").asString()).isEqualTo(viewerId);
		assertThat(answer.path("sdp").asString()).isEqualTo(SDP);

		viewer.send("{\"type\":\"ice\",\"candidate\":" + CANDIDATE + "}");
		JsonNode iceFromViewer = publisher.next("ice");
		assertThat(iceFromViewer.path("viewerId").asString()).isEqualTo(viewerId);
		assertThat(iceFromViewer.path("candidate").path("sdpMLineIndex").asInt()).isZero();

		publisher.send("{\"type\":\"ice\",\"viewerId\":\"" + viewerId + "\",\"candidate\":" + CANDIDATE + "}");
		JsonNode iceFromPublisher = viewer.next("ice");
		assertThat(iceFromPublisher.path("candidate").path("candidate").asString()).startsWith("candidate:1 1 udp");
	}

	@Test
	void endOfCandidatesIsRelayed() throws Exception {
		SignalingTestClient publisher = connect();
		long broadcastId = start(publisher, "라이브");
		SignalingTestClient viewer = connect();
		String viewerId = watch(viewer, publisher, broadcastId);

		publisher.send("{\"type\":\"ice\",\"viewerId\":\"" + viewerId + "\",\"candidate\":null}");

		assertThat(viewer.next("ice").path("candidate").isNull()).isTrue();
	}

	@Test
	void publisherCannotReachViewerOfAnotherBroadcast() throws Exception {
		SignalingTestClient publisherA = connect();
		start(publisherA, "방송 A");
		SignalingTestClient publisherB = connect();
		long broadcastB = start(publisherB, "방송 B");
		SignalingTestClient viewerOfB = connect();
		String viewerIdOfB = watch(viewerOfB, publisherB, broadcastB);

		publisherA.send("{\"type\":\"offer\",\"viewerId\":\"" + viewerIdOfB + "\",\"sdp\":" + quote(SDP) + "}");
		publisherA.send("{\"type\":\"ice\",\"viewerId\":\"" + viewerIdOfB + "\",\"candidate\":" + CANDIDATE + "}");

		publisherA.expectError("UNKNOWN_VIEWER");
		publisherA.expectError("UNKNOWN_VIEWER");
		viewerOfB.expectNothing();
		publisherB.expectNothing();
	}

	@Test
	void watchingMissingRecordedOrInvalidBroadcastIsRejected() throws Exception {
		Long recordedId = broadcastRepository.save(
			new Broadcast("녹화", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4")).getId();
		SignalingTestClient viewer = connect();

		viewer.send("{\"type\":\"watch\",\"broadcastId\":99999}");
		viewer.expectError("NOT_FOUND");
		viewer.send("{\"type\":\"watch\",\"broadcastId\":" + recordedId + "}");
		viewer.expectError("NOT_LIVE");
		viewer.send("{\"type\":\"watch\",\"broadcastId\":\"abc\"}");
		viewer.expectError("INVALID_MESSAGE");
		viewer.send("{\"type\":\"watch\",\"broadcastId\":-1}");
		viewer.expectError("INVALID_MESSAGE");
		viewer.send("{\"type\":\"watch\"}");
		viewer.expectError("INVALID_MESSAGE");
	}

	@Test
	void malformedMessagesAreRejected() throws Exception {
		SignalingTestClient client = connect();

		client.send("not json");
		client.expectError("INVALID_MESSAGE");
		client.send("[1,2,3]");
		client.expectError("INVALID_MESSAGE");
		client.send("{\"title\":\"type 없음\"}");
		client.expectError("INVALID_MESSAGE");
		client.send("{\"type\":\"dance\"}");
		client.expectError("INVALID_MESSAGE");
		assertThat(client.isOpen()).isTrue();
	}

	@Test
	void oversizedOrMalformedSignalingPayloadIsRejected() throws Exception {
		SignalingTestClient publisher = connect();
		long broadcastId = start(publisher, "라이브");
		SignalingTestClient viewer = connect();
		String viewerId = watch(viewer, publisher, broadcastId);

		publisher.send("{\"type\":\"offer\",\"viewerId\":\"" + viewerId + "\",\"sdp\":" + quote("a".repeat(33 * 1024)) + "}");
		publisher.expectError("INVALID_MESSAGE");
		publisher.send("{\"type\":\"offer\",\"viewerId\":\"" + viewerId + "\",\"sdp\":123}");
		publisher.expectError("INVALID_MESSAGE");
		viewer.send("{\"type\":\"ice\",\"candidate\":{\"candidate\":" + quote("c".repeat(2000)) + "}}");
		viewer.expectError("INVALID_MESSAGE");
		viewer.send("{\"type\":\"ice\",\"candidate\":\"not an object\"}");
		viewer.expectError("INVALID_MESSAGE");

		viewer.expectNothing();
		publisher.expectNothing();
	}

	@Test
	void messagesThatDoNotMatchTheConnectionRoleAreRejected() throws Exception {
		SignalingTestClient publisher = connect();
		long broadcastId = start(publisher, "라이브");
		SignalingTestClient viewer = connect();
		watch(viewer, publisher, broadcastId);
		SignalingTestClient stranger = connect();

		publisher.send("{\"type\":\"start\",\"title\":\"두 번째 방송\"}");
		publisher.expectError("INVALID_ROLE");
		publisher.send("{\"type\":\"watch\",\"broadcastId\":" + broadcastId + "}");
		publisher.expectError("INVALID_ROLE");
		viewer.send("{\"type\":\"offer\",\"viewerId\":\"x\",\"sdp\":" + quote(SDP) + "}");
		viewer.expectError("INVALID_ROLE");
		viewer.send("{\"type\":\"end\"}");
		viewer.expectError("INVALID_ROLE");
		stranger.send("{\"type\":\"answer\",\"sdp\":" + quote(SDP) + "}");
		stranger.expectError("INVALID_ROLE");
		stranger.send("{\"type\":\"heartbeat\"}");
		stranger.expectError("INVALID_ROLE");

		assertThat(broadcastRepository.count()).isEqualTo(1);
		assertThat(broadcastRepository.findById(broadcastId).orElseThrow().getLiveStatus()).isEqualTo(LiveStatus.LIVE);
	}

	@Test
	void viewersBeyondTheLimitAreRejected() throws Exception {
		SignalingTestClient publisher = connect();
		long broadcastId = start(publisher, "라이브");
		watch(connect(), publisher, broadcastId);
		watch(connect(), publisher, broadcastId);
		SignalingTestClient third = connect();

		third.send("{\"type\":\"watch\",\"broadcastId\":" + broadcastId + "}");

		third.expectError("FULL");
		publisher.expectNothing();
	}

	@Test
	void endMessageEndsBroadcastAndClosesViewers() throws Exception {
		SignalingTestClient publisher = connect();
		long broadcastId = start(publisher, "라이브");
		SignalingTestClient viewer = connect();
		watch(viewer, publisher, broadcastId);

		publisher.send("{\"type\":\"end\"}");

		viewer.next("ended");
		publisher.next("ended");
		viewer.awaitClosed();
		publisher.awaitClosed();
		Broadcast ended = broadcastRepository.findById(broadcastId).orElseThrow();
		assertThat(ended.getLiveStatus()).isEqualTo(LiveStatus.ENDED);
		assertThat(ended.getEndedAt()).isNotNull();
	}

	@Test
	void publisherDisconnectEndsBroadcastAndNotifiesViewers() throws Exception {
		SignalingTestClient publisher = connect();
		long broadcastId = start(publisher, "라이브");
		SignalingTestClient viewer = connect();
		watch(viewer, publisher, broadcastId);

		publisher.close();

		viewer.next("ended");
		viewer.awaitClosed();
		assertThat(broadcastRepository.findById(broadcastId).orElseThrow().getLiveStatus())
			.isEqualTo(LiveStatus.ENDED);
	}

	@Test
	void endedBroadcastCannotBeWatchedAgain() throws Exception {
		SignalingTestClient publisher = connect();
		long broadcastId = start(publisher, "라이브");
		publisher.send("{\"type\":\"end\"}");
		publisher.next("ended");

		SignalingTestClient lateViewer = connect();
		lateViewer.send("{\"type\":\"watch\",\"broadcastId\":" + broadcastId + "}");

		lateViewer.expectError("NOT_LIVE");
	}

	@Test
	void viewerDisconnectIsReportedToPublisher() throws Exception {
		SignalingTestClient publisher = connect();
		long broadcastId = start(publisher, "라이브");
		SignalingTestClient viewer = connect();
		String viewerId = watch(viewer, publisher, broadcastId);

		viewer.close();

		assertThat(publisher.next("viewer-left").path("viewerId").asString()).isEqualTo(viewerId);
		assertThat(broadcastRepository.findById(broadcastId).orElseThrow().getLiveStatus()).isEqualTo(LiveStatus.LIVE);
	}

	@Test
	void heartbeatKeepsBroadcastAlive() throws Exception {
		SignalingTestClient publisher = connect();
		long broadcastId = start(publisher, "라이브");
		var before = broadcastRepository.findById(broadcastId).orElseThrow().getLastHeartbeatAt();
		Thread.sleep(20);

		publisher.send("{\"type\":\"heartbeat\"}");

		publisher.expectNothing();
		assertThat(broadcastRepository.findById(broadcastId).orElseThrow().getLastHeartbeatAt()).isAfter(before);
	}

	@Test
	void handshakeFromAnotherOriginIsRejected() {
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.setOrigin("http://evil.example");

		assertThatThrownBy(() -> new StandardWebSocketClient()
			.execute(new SignalingTestClient(), headers, URI.create("ws://localhost:" + port + "/ws/live"))
			.get(5, TimeUnit.SECONDS))
			.isInstanceOf(ExecutionException.class);
	}

	private SignalingTestClient connect() throws Exception {
		SignalingTestClient client = SignalingTestClient.connect(port);
		clients.add(client);
		return client;
	}

	private static long start(SignalingTestClient publisher, String title) throws Exception {
		publisher.send("{\"type\":\"start\",\"title\":" + quote(title) + "}");
		return publisher.next("started").path("broadcastId").asLong();
	}

	private static String watch(SignalingTestClient viewer, SignalingTestClient publisher, long broadcastId)
		throws Exception {
		viewer.send("{\"type\":\"watch\",\"broadcastId\":" + broadcastId + "}");
		viewer.next("watching");
		return publisher.next("viewer-joined").path("viewerId").asString();
	}

	private static String quote(String value) {
		return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n") + "\"";
	}

}
