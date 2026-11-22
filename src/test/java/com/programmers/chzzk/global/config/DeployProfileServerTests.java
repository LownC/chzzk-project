package com.programmers.chzzk.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.broadcast.BroadcastRepository;
import com.programmers.chzzk.payment.FakePaymentGateway;
import com.programmers.chzzk.payment.PaymentGateway;

/**
 * (⑥) 배포(deploy) 프로파일을 실제 Tomcat으로 띄워, 같은 VM의 nginx가 보내는 요청처럼 확인한다.
 * Host·X-Forwarded-* 헤더를 그대로 보내려고 소켓으로 HTTP를 쓴다. 테스트 요청은 127.0.0.1에서 오므로 신뢰하는 프록시다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("deploy")
class DeployProfileServerTests {

	private static final String PUBLIC_HOST = "chzzk.lowndev.com";
	private static final String VIDEO_FILE_NAME = "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4";

	@TempDir
	static Path dataDirectory;

	@TempDir
	static Path uploadDirectory;

	@DynamicPropertySource
	static void deployEnvironment(DynamicPropertyRegistry registry) {
		registry.add("APP_DATA_DIR", dataDirectory::toString);
		registry.add("APP_UPLOAD_DIR", uploadDirectory::toString);
		registry.add("CHZZK_DB_PASSWORD", () -> "deploy-test-only");
		registry.add("app.live.expiry-check-enabled", () -> "false");
	}

	@LocalServerPort
	private int port;

	@Autowired
	private Environment environment;

	@Autowired
	private PaymentGateway paymentGateway;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@Test
	void apiUrlsUseTheForwardedHttpsHost() throws IOException {
		broadcastRepository.save(new Broadcast("배포 방송", VIDEO_FILE_NAME));

		String response = send("GET /api/broadcasts HTTP/1.1\r\n"
			+ "Host: " + PUBLIC_HOST + "\r\n"
			+ "X-Forwarded-Proto: https\r\n"
			+ "X-Forwarded-For: 203.0.113.10\r\n"
			+ "Connection: close\r\n\r\n", true);

		assertThat(response).startsWith("HTTP/1.1 200");
		assertThat(response).contains("\"playbackUrl\":\"https://" + PUBLIC_HOST + "/videos/" + VIDEO_FILE_NAME + "\"");
	}

	@Test
	void liveWebSocketFromTheHttpsPageIsAcceptedBehindTheProxy() throws IOException {
		String response = send(webSocketHandshake("X-Forwarded-Proto: https\r\n"), false);

		assertThat(response).startsWith("HTTP/1.1 101");
	}

	@Test
	void withoutTheForwardedProtoTheHttpsOriginDoesNotMatch() throws IOException {
		// 전달 헤더를 반영하지 않으면 https 페이지에서 온 연결이 다른 출처로 보여 거부된다(이 설정이 필요한 이유).
		String response = send(webSocketHandshake(""), false);

		assertThat(response).startsWith("HTTP/1.1 403");
	}

	@Test
	void sessionCookieIsSecureAndH2ConsoleIsOff() throws IOException {
		String login = send("GET /login HTTP/1.1\r\nHost: " + PUBLIC_HOST + "\r\n"
			+ "X-Forwarded-Proto: https\r\nConnection: close\r\n\r\n", true);
		String console = send("GET /h2-console HTTP/1.1\r\nHost: " + PUBLIC_HOST + "\r\n"
			+ "X-Forwarded-Proto: https\r\nConnection: close\r\n\r\n", true);

		assertThat(login).startsWith("HTTP/1.1 200");
		assertThat(login).containsPattern("(?i)Set-Cookie: JSESSIONID=[^\\r\\n]*; Secure");
		assertThat(console).startsWith("HTTP/1.1 404");
	}

	@Test
	void keepsUploadLimitFakePaymentAndLoopbackBinding() {
		assertThat(environment.getProperty("spring.servlet.multipart.max-file-size")).isEqualTo("500MB");
		assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
		assertThat(paymentGateway).isInstanceOf(FakePaymentGateway.class);
	}

	private static String webSocketHandshake(String forwardedHeaders) {
		return "GET /ws/live HTTP/1.1\r\n"
			+ "Host: " + PUBLIC_HOST + "\r\n"
			+ forwardedHeaders
			+ "Origin: https://" + PUBLIC_HOST + "\r\n"
			+ "Upgrade: websocket\r\n"
			+ "Connection: Upgrade\r\n"
			+ "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
			+ "Sec-WebSocket-Version: 13\r\n\r\n";
	}

	/**
	 * readToEnd가 false면 응답 헤더까지만 읽는다(WebSocket 101은 연결이 닫히지 않는다).
	 */
	private String send(String request, boolean readToEnd) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", port)) {
			socket.setSoTimeout(5000);
			OutputStream out = socket.getOutputStream();
			out.write(request.getBytes(StandardCharsets.UTF_8));
			out.flush();
			InputStream in = socket.getInputStream();
			ByteArrayOutputStream received = new ByteArrayOutputStream();
			int b;
			while ((b = in.read()) != -1) {
				received.write(b);
				if (!readToEnd && received.toString(StandardCharsets.UTF_8).endsWith("\r\n\r\n")) {
					break;
				}
			}
			return received.toString(StandardCharsets.UTF_8);
		}
	}

}
