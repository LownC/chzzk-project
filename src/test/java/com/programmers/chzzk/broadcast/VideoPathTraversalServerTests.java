package com.programmers.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 실제 Tomcat에 원시 경로를 그대로 보내 업로드 폴더 밖 파일이 노출되지 않는지 검증한다(S2-2).
 * 업로드 폴더는 임시 폴더의 하위 폴더이고, 표식 파일은 그 바깥(임시 폴더 바로 아래)에 둔다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class VideoPathTraversalServerTests {

	private static final String VIDEO_FILE_NAME = "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4";
	private static final String OUTSIDE_FILE_NAME = "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d.mp4";
	private static final String MARKER = "OUTSIDE-UPLOAD-FOLDER-MARKER";
	private static final String VIDEO_CONTENT = "inside-upload-folder-video";

	@TempDir
	static Path tempRoot;

	@DynamicPropertySource
	static void uploadDirectoryProperty(DynamicPropertyRegistry registry) {
		registry.add("app.file.upload-dir", () -> tempRoot.resolve("uploads").toString());
	}

	@LocalServerPort
	private int port;

	private final HttpClient httpClient = HttpClient.newHttpClient();

	@BeforeAll
	static void createFiles() throws IOException {
		Files.createDirectories(tempRoot.resolve("uploads"));
		Files.writeString(tempRoot.resolve("uploads").resolve(VIDEO_FILE_NAME), VIDEO_CONTENT);
		Files.writeString(tempRoot.resolve(OUTSIDE_FILE_NAME), MARKER);
		Files.writeString(tempRoot.resolve("marker.txt"), MARKER);
	}

	@Test
	void videoInsideUploadFolderIsServedByRealServer() throws Exception {
		HttpResponse<String> response = send("/videos/" + VIDEO_FILE_NAME, null);

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type")).hasValue("video/mp4");
		assertThat(response.body()).isEqualTo(VIDEO_CONTENT);
	}

	@Test
	void rangeRequestReturnsPartialContentFromRealServer() throws Exception {
		HttpResponse<String> response = send("/videos/" + VIDEO_FILE_NAME, "bytes=0-9");

		assertThat(response.statusCode()).isEqualTo(206);
		assertThat(response.headers().firstValue("Content-Range"))
			.hasValue("bytes 0-9/" + VIDEO_CONTENT.length());
		assertThat(response.body()).isEqualTo(VIDEO_CONTENT.substring(0, 10));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"/videos/%2e%2e%2f" + OUTSIDE_FILE_NAME,
		"/videos/..%2F" + OUTSIDE_FILE_NAME,
		"/videos/..%2f" + OUTSIDE_FILE_NAME,
		"/videos/%2F..%2F" + OUTSIDE_FILE_NAME,
		"/videos/..%5C" + OUTSIDE_FILE_NAME,
		"/videos/..%252F" + OUTSIDE_FILE_NAME,
		"/videos/%2e%2e/" + OUTSIDE_FILE_NAME,
		"/videos/../" + OUTSIDE_FILE_NAME,
		"/videos/..%2Fmarker.txt",
		"/videos/%2e%2e%2fmarker.txt",
		"/videos/../marker.txt"
	})
	void encodedTraversalDoesNotExposeFileOutsideUploadFolder(String rawPath) throws Exception {
		HttpResponse<String> response = send(rawPath, null);

		assertThat(response.statusCode()).isNotEqualTo(200).isNotEqualTo(206);
		assertThat(response.body()).doesNotContain(MARKER);
	}

	/**
	 * 클라이언트가 경로를 정규화하지 않도록 원시 경로 문자열로 URI를 만든다.
	 */
	private HttpResponse<String> send(String rawPath, String range) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath)).GET();
		if (range != null) {
			request.header("Range", range);
		}
		return httpClient.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

}
