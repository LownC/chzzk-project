package com.programmers.chzzk.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.programmers.chzzk.TestHttpLogin;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 시그널링 서버 테스트용 WebSocket 클라이언트. 받은 메시지를 순서대로 쌓아 두고 꺼내 본다.
 */
final class SignalingTestClient extends TextWebSocketHandler implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final Duration WAIT = Duration.ofSeconds(5);

	private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
	private final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();
	private WebSocketSession session;

	/**
	 * 로그인하지 않은 연결(시청자).
	 */
	static SignalingTestClient connect(int port) throws Exception {
		SignalingTestClient client = new SignalingTestClient();
		client.session = new StandardWebSocketClient()
			.execute(client, "ws://localhost:" + port + "/ws/live")
			.get(WAIT.toSeconds(), TimeUnit.SECONDS);
		return client;
	}

	/**
	 * 로그인한 브라우저처럼 세션 쿠키를 실어 연결한다. 쿠키는 {@link #login}으로 얻는다.
	 */
	static SignalingTestClient connect(int port, String sessionCookie) throws Exception {
		SignalingTestClient client = new SignalingTestClient();
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.add(HttpHeaders.COOKIE, sessionCookie);
		client.session = new StandardWebSocketClient()
			.execute(client, headers, URI.create("ws://localhost:" + port + "/ws/live"))
			.get(WAIT.toSeconds(), TimeUnit.SECONDS);
		return client;
	}

	/**
	 * 실제 로그인 화면으로 폼 로그인을 하고, 로그인 뒤 세션 쿠키("JSESSIONID=...")를 돌려준다.
	 * 로그인 화면의 CSRF 토큰을 함께 보내야 로그인이 받아들여진다.
	 */
	static String login(int port, String username, String password) throws Exception {
		return TestHttpLogin.login(port, username, password);
	}

	void send(String json) throws Exception {
		session.sendMessage(new TextMessage(json));
	}

	JsonNode next() throws InterruptedException {
		JsonNode message = received.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
		assertThat(message).as("기다린 메시지가 오지 않음").isNotNull();
		return message;
	}

	JsonNode next(String expectedType) throws InterruptedException {
		JsonNode message = next();
		assertThat(message.path("type").asString()).as("메시지: %s", message).isEqualTo(expectedType);
		return message;
	}

	/**
	 * 다른 종류의 메시지는 건너뛰고 기다리는 종류의 메시지를 꺼낸다(도착 순서가 정해지지 않은 경우).
	 */
	JsonNode nextSkipping(String expectedType) throws InterruptedException {
		for (;;) {
			JsonNode message = next();
			if (message.path("type").asString().equals(expectedType)) {
				return message;
			}
		}
	}

	void expectError(String expectedCode) throws InterruptedException {
		JsonNode error = next("error");
		assertThat(error.path("code").asString()).isEqualTo(expectedCode);
		assertThat(error.path("message").asString()).isNotBlank();
	}

	void expectNothing() throws InterruptedException {
		assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();
	}

	CloseStatus awaitClosed() throws Exception {
		return closed.get(WAIT.toSeconds(), TimeUnit.SECONDS);
	}

	boolean isOpen() {
		return session.isOpen();
	}

	@Override
	protected void handleTextMessage(WebSocketSession session, TextMessage message) {
		received.add(JSON.readTree(message.getPayload()));
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
		closed.complete(status);
	}

	@Override
	public void close() throws Exception {
		if (session.isOpen()) {
			session.close();
		}
	}

}
