package com.programmers.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import java.io.IOException;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BroadcastRegistrationValidationTests {

	private static final byte[] VIDEO_CONTENT = "fake mp4 bytes".getBytes(StandardCharsets.UTF_8);

	@TempDir
	static Path uploadDirectory;

	@DynamicPropertySource
	static void uploadDirectoryProperty(DynamicPropertyRegistry registry) {
		registry.add("app.file.upload-dir", uploadDirectory::toString);
	}

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@BeforeEach
	void clearBroadcasts() {
		broadcastRepository.deleteAll();
	}

	@AfterEach
	void failedRegistrationLeavesNothingBehind() throws IOException {
		assertThat(broadcastRepository.count()).isZero();
		try (Stream<Path> files = Files.list(uploadDirectory)) {
			assertThat(files).isEmpty();
		}
	}

	@Test
	void blankTitleIsRejectedWithJsonForXhr() throws Exception {
		registerByXhr("   ", mp4File("video.mp4"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.*", hasSize(1)))
			.andExpect(jsonPath("$.message").value("제목을 입력해 주세요."))
			.andExpect(content().string(not(containsString("Exception"))));
	}

	@Test
	void titleLongerThan100CharactersIsRejected() throws Exception {
		registerByXhr("가".repeat(101), mp4File("video.mp4"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("제목은 최대 100자까지 입력할 수 있습니다."));
	}

	@Test
	void requestWithoutFilePartIsRejectedAsNoFileSelected() throws Exception {
		mockMvc.perform(multipart("/broadcasts")
				.param("title", "파일 없음")
				.header("X-Requested-With", "XMLHttpRequest"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("영상 파일을 선택해 주세요."));
	}

	@Test
	void filePartWithEmptyFilenameIsRejectedAsNoFileSelected() throws Exception {
		registerByXhr("파일 미선택", new MockMultipartFile("videoFile", "", "application/octet-stream", new byte[0]))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("영상 파일을 선택해 주세요."));
	}

	@Test
	void zeroByteFileIsRejected() throws Exception {
		registerByXhr("빈 파일", new MockMultipartFile("videoFile", "empty.mp4", "video/mp4", new byte[0]))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("영상 파일이 비어 있습니다."));
	}

	@Test
	void mp4ExtensionWithNonMp4ContentTypeIsRejected() throws Exception {
		registerByXhr("형식 불일치", new MockMultipartFile("videoFile", "video.mp4", "video/quicktime", VIDEO_CONTENT))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("MP4 형식의 영상 파일만 업로드할 수 있습니다."));
	}

	@Test
	void missingTitleIsRejectedOnRegistrationPageForForm() throws Exception {
		mockMvc.perform(multipart("/broadcasts").file(mp4File("video.mp4")))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
			.andExpect(xpath("//form[@action='/broadcasts']").exists())
			.andExpect(content().string(containsString("제목을 입력해 주세요.")))
			.andExpect(content().string(not(containsString("Exception"))));
	}

	@Test
	void formErrorKeepsSubmittedTitle() throws Exception {
		mockMvc.perform(multipart("/broadcasts")
				.file(new MockMultipartFile("videoFile", "clip.mov", "video/quicktime", VIDEO_CONTENT))
				.param("title", "  유지될 제목 "))
			.andExpect(status().isBadRequest())
			.andExpect(xpath("//input[@name='title']/@value").string("  유지될 제목 "))
			.andExpect(content().string(containsString("MP4 형식의 영상 파일만 업로드할 수 있습니다.")));
	}

	@Test
	void requestsOutsideRegistrationKeepDefaultErrorHandling() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/no-such-page"))
			.andExpect(status().isNotFound());
	}

	private ResultActions registerByXhr(String title, MockMultipartFile videoFile) throws Exception {
		return mockMvc.perform(multipart("/broadcasts")
			.file(videoFile)
			.param("title", title)
			.header("X-Requested-With", "XMLHttpRequest"));
	}

	private static MockMultipartFile mp4File(String originalFilename) {
		return new MockMultipartFile("videoFile", originalFilename, "video/mp4", VIDEO_CONTENT);
	}

}
