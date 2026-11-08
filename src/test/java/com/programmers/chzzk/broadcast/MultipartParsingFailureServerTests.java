package com.programmers.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 Tomcat의 multipart 해석 단계에서 거부되는 요청을 검증한다(S2-1, S2-3).
 * 이 클래스에서만 multipart 한도를 KB 단위로 낮춰 실제 500MB를 보내지 않는다.
 */
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"spring.servlet.multipart.max-file-size=1KB",
		"spring.servlet.multipart.max-request-size=2KB"
	})
@ActiveProfiles("test")
class MultipartParsingFailureServerTests {

	private static final String BOUNDARY = "chzzkTestBoundary";
	private static final String SIZE_EXCEEDED_MESSAGE = "영상 파일은 최대 500MB까지 업로드할 수 있습니다.";
	private static final String REGISTRATION_FAILED_MESSAGE = "방송 등록에 실패했습니다. 잠시 후 다시 시도해 주세요.";

	@TempDir
	static Path uploadDirectory;

	@DynamicPropertySource
	static void uploadDirectoryProperty(DynamicPropertyRegistry registry) {
		registry.add("app.file.upload-dir", uploadDirectory::toString);
	}

	@LocalServerPort
	private int port;

	@Autowired
	private BroadcastRepository broadcastRepository;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@BeforeEach
	void clearBroadcasts() {
		broadcastRepository.deleteAll();
	}

	@AfterEach
	void rejectedRequestLeavesNothingBehind() throws IOException {
		assertThat(broadcastRepository.count()).isZero();
		try (Stream<Path> files = Files.list(uploadDirectory)) {
			assertThat(files).isEmpty();
		}
	}

	@Test
	void oversizedUploadIsRejectedWithSizeMessageForXhr() throws Exception {
		HttpResponse<String> response = post(oversizedMultipartBody(), true);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
			contentType -> assertThat(contentType).startsWith("application/json"));
		assertThat(response.body()).isEqualTo("{\"message\":\"" + SIZE_EXCEEDED_MESSAGE + "\"}");
	}

	@Test
	void oversizedUploadShowsRegistrationPageForForm() throws Exception {
		HttpResponse<String> response = post(oversizedMultipartBody(), false);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
			contentType -> assertThat(contentType).startsWith("text/html"));
		assertThat(response.body()).contains("action=\"/broadcasts\"", SIZE_EXCEEDED_MESSAGE);
		assertNoInternalDetails(response.body());
	}

	@Test
	void malformedMultipartIsRejectedWithRegistrationFailureForXhr() throws Exception {
		HttpResponse<String> response = post(malformedMultipartBody(), true);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
			contentType -> assertThat(contentType).startsWith("application/json"));
		assertThat(response.body()).isEqualTo("{\"message\":\"" + REGISTRATION_FAILED_MESSAGE + "\"}");
	}

	@Test
	void malformedMultipartShowsRegistrationPageForForm() throws Exception {
		HttpResponse<String> response = post(malformedMultipartBody(), false);

		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).contains("action=\"/broadcasts\"", REGISTRATION_FAILED_MESSAGE);
		assertThat(response.body()).doesNotContain(SIZE_EXCEEDED_MESSAGE);
		assertNoInternalDetails(response.body());
	}

	private HttpResponse<String> post(byte[] body, boolean xhr) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/broadcasts"))
			.header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		if (xhr) {
			request.header("X-Requested-With", "XMLHttpRequest");
		}
		return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	/**
	 * 정상 형식이지만 영상 파트가 1KB 한도를 넘는 본문. 요청 전체는 Tomcat이 끝까지 읽을 수 있는 크기다.
	 */
	private static byte[] oversizedMultipartBody() throws IOException {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.write(("--" + BOUNDARY + "\r\n"
			+ "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
			+ "용량 초과 방송\r\n"
			+ "--" + BOUNDARY + "\r\n"
			+ "Content-Disposition: form-data; name=\"videoFile\"; filename=\"video.mp4\"\r\n"
			+ "Content-Type: video/mp4\r\n\r\n").getBytes(StandardCharsets.UTF_8));
		body.write(new byte[1500]);
		body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return body.toByteArray();
	}

	/**
	 * 파트를 시작한 뒤 닫는 boundary 없이 끝나는 깨진 본문. 크기는 용량 한도보다 작다.
	 */
	private static byte[] malformedMultipartBody() {
		return ("--" + BOUNDARY + "\r\n"
			+ "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
			+ "깨진 요청").getBytes(StandardCharsets.UTF_8);
	}

	private static void assertNoInternalDetails(String body) {
		assertThat(body).doesNotContain("Exception", "at org.", "at java.", "Caused by", uploadDirectory.toString());
	}

}
