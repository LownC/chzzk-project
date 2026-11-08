package com.programmers.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

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
class BroadcastRegistrationTests {

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
	void clearBroadcastsAndVideoFiles() throws IOException {
		broadcastRepository.deleteAll();
		for (Path file : storedVideoFiles()) {
			Files.delete(file);
		}
	}

	@Test
	void registrationPageHasMultipartForm() throws Exception {
		mockMvc.perform(get("/broadcasts/new"))
			.andExpect(status().isOk())
			.andExpect(xpath("//form[@method='post'][@action='/broadcasts'][@enctype='multipart/form-data']").exists())
			.andExpect(xpath("//form//input[@name='title']").exists())
			.andExpect(xpath("//form//input[@type='file'][@name='videoFile']").exists())
			.andExpect(xpath("//form//button[@type='submit']").exists())
			.andExpect(xpath("//header//a[@href='/broadcasts']").exists());
	}

	@Test
	void registrationPageHasUploadProgressErrorAreaAndUploadScript() throws Exception {
		mockMvc.perform(get("/broadcasts/new"))
			.andExpect(status().isOk())
			.andExpect(xpath("//main//progress[@max='100']").exists())
			.andExpect(xpath("//main//*[@class='upload-percent']").string("0%"))
			.andExpect(xpath("//main//*[@class='error-message']").exists())
			.andExpect(xpath("//script[@src='/js/broadcast-upload.js']").exists());
	}

	/**
	 * 업로드 스크립트가 spec의 XHR 헤더, 30분 timeout, 500MB 사전 차단 기준을 담고 있는지 확인한다.
	 */
	@Test
	void uploadScriptUsesXhrHeaderTimeoutAndSizeLimitFromSpec() throws Exception {
		mockMvc.perform(get("/js/broadcast-upload.js"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("X-Requested-With")))
			.andExpect(content().string(containsString("1800000")))
			.andExpect(content().string(containsString("524288000")));
	}

	@Test
	void xhrRegistrationReturnsCreatedJsonAndStoresBroadcastWithVideoFile() throws Exception {
		ResultActions response = mockMvc.perform(multipart("/broadcasts")
				.file(mp4File("video.mp4"))
				.param("title", "  첫 방송  ")
				.header("X-Requested-With", "XMLHttpRequest"))
			.andExpect(status().isCreated())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.*", hasSize(2)));

		List<Broadcast> broadcasts = broadcastRepository.findAll();
		assertThat(broadcasts).hasSize(1);
		Broadcast broadcast = broadcasts.getFirst();
		response
			.andExpect(jsonPath("$.broadcastId").value(broadcast.getId()))
			.andExpect(jsonPath("$.redirectUrl").value("/broadcasts/" + broadcast.getId()));
		assertThat(broadcast.getTitle()).isEqualTo("첫 방송");
		assertThat(storedVideoFiles()).singleElement()
			.satisfies(file -> {
				assertThat(file.getFileName().toString())
					.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.mp4")
					.isEqualTo(broadcast.getVideoFileName());
				assertThat(file).hasBinaryContent(VIDEO_CONTENT);
			});
	}

	@Test
	void formRegistrationRedirectsToDetailPage() throws Exception {
		ResultActions response = mockMvc.perform(multipart("/broadcasts")
				.file(mp4File("video.mp4"))
				.param("title", "폼 방송"))
			.andExpect(status().isFound());

		Broadcast broadcast = broadcastRepository.findAll().getFirst();
		response.andExpect(redirectedUrl("/broadcasts/" + broadcast.getId()));
		assertThat(storedVideoFiles()).hasSize(1);
	}

	@Test
	void titleOfExactly100CharactersIsAccepted() throws Exception {
		String title = "가".repeat(100);

		registerByXhr(title).andExpect(status().isCreated());

		assertThat(broadcastRepository.findAll().getFirst().getTitle()).isEqualTo(title);
	}

	@Test
	void titleIs100CharactersAfterStrippingSurroundingSpaces() throws Exception {
		String title = "가".repeat(100);

		registerByXhr("  " + title + "  ").andExpect(status().isCreated());

		assertThat(broadcastRepository.findAll().getFirst().getTitle()).isEqualTo(title);
	}

	@Test
	void uppercaseMp4ExtensionIsAccepted() throws Exception {
		mockMvc.perform(multipart("/broadcasts")
				.file(mp4File("VIDEO.MP4"))
				.param("title", "대문자 확장자")
				.header("X-Requested-With", "XMLHttpRequest"))
			.andExpect(status().isCreated());

		assertThat(broadcastRepository.count()).isEqualTo(1);
		assertThat(storedVideoFiles()).singleElement()
			.satisfies(file -> assertThat(file.getFileName().toString()).endsWith(".mp4"));
	}

	private ResultActions registerByXhr(String title) throws Exception {
		return mockMvc.perform(multipart("/broadcasts")
			.file(mp4File("video.mp4"))
			.param("title", title)
			.header("X-Requested-With", "XMLHttpRequest"));
	}

	private static MockMultipartFile mp4File(String originalFilename) {
		return new MockMultipartFile("videoFile", originalFilename, "video/mp4", VIDEO_CONTENT);
	}

	private static List<Path> storedVideoFiles() throws IOException {
		try (Stream<Path> files = Files.list(uploadDirectory)) {
			return files.toList();
		}
	}

}
