package com.programmers.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 브라우저가 영상 Range 요청을 도중에 끊는 상황을 실제 서버로 재현한다.
 * 정상적인 연결 중단은 ERROR로 남지 않아야 한다.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = "logging.level.com.programmers.chzzk.global.exception=DEBUG")
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class VideoRequestAbortServerTests {

	private static final String VIDEO_FILE_NAME = "5e6f7a8b-9c0d-4e1f-8a2b-3c4d5e6f7a8b.mp4";
	private static final long VIDEO_SIZE = 32L * 1024 * 1024;
	private static final String DISCONNECT_LOG = "클라이언트 연결 중단";
	private static final String UNEXPECTED_ERROR_LOG = "예상하지 못한 오류";

	@TempDir
	static Path uploadDirectory;

	@DynamicPropertySource
	static void uploadDirectoryProperty(DynamicPropertyRegistry registry) {
		registry.add("app.file.upload-dir", uploadDirectory::toString);
	}

	@LocalServerPort
	private int port;

	@BeforeAll
	static void createLargeVideo() throws IOException {
		try (RandomAccessFile file = new RandomAccessFile(uploadDirectory.resolve(VIDEO_FILE_NAME).toFile(), "rw")) {
			file.setLength(VIDEO_SIZE);
		}
	}

	@Test
	void clientAbortDuringVideoDownloadIsNotLoggedAsError(CapturedOutput output) throws Exception {
		requestVideoAndResetConnection();

		waitUntilLogged(output);
		assertThat(output.getOut())
			.contains(DISCONNECT_LOG)
			.doesNotContain(UNEXPECTED_ERROR_LOG)
			.doesNotContain("getOutputStream() has already been called");
	}

	/**
	 * 응답을 조금만 읽고 RST로 연결을 끊어, 서버가 남은 영상을 쓰다가 연결 중단을 겪게 한다.
	 */
	private void requestVideoAndResetConnection() throws IOException {
		try (Socket socket = new Socket()) {
			socket.setReceiveBufferSize(4096);
			socket.connect(new InetSocketAddress("localhost", port));
			OutputStream out = socket.getOutputStream();
			out.write(("GET /videos/" + VIDEO_FILE_NAME + " HTTP/1.1\r\n"
				+ "Host: localhost\r\n"
				+ "Range: bytes=0-\r\n"
				+ "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			out.flush();
			socket.getInputStream().readNBytes(1024);
			socket.setSoLinger(true, 0);
		}
	}

	private static void waitUntilLogged(CapturedOutput output) throws InterruptedException {
		long deadline = System.nanoTime() + 10_000_000_000L;
		while (System.nanoTime() < deadline) {
			String logged = output.getOut();
			if (logged.contains(DISCONNECT_LOG) || logged.contains(UNEXPECTED_ERROR_LOG)) {
				return;
			}
			Thread.sleep(50);
		}
	}

}
