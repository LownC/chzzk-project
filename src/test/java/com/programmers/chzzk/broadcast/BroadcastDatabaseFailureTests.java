package com.programmers.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
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
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BroadcastDatabaseFailureTests {

	private static final MockMultipartFile VIDEO_FILE =
		new MockMultipartFile("videoFile", "video.mp4", "video/mp4", "fake mp4 bytes".getBytes(StandardCharsets.UTF_8));

	@TempDir
	static Path uploadDirectory;

	@DynamicPropertySource
	static void uploadDirectoryProperty(DynamicPropertyRegistry registry) {
		registry.add("app.file.upload-dir", uploadDirectory::toString);
	}

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private BroadcastRepository broadcastRepository;

	@BeforeEach
	void databaseRejectsSaving() {
		when(broadcastRepository.save(any())).thenThrow(new DataAccessResourceFailureException("connection refused"));
	}

	@AfterEach
	void storedVideoFileIsDeletedByCompensation() throws IOException {
		try (Stream<Path> files = Files.list(uploadDirectory)) {
			assertThat(files).isEmpty();
		}
	}

	@Test
	void databaseFailureReturnsRegistrationFailureJsonForXhr() throws Exception {
		mockMvc.perform(multipart("/broadcasts").file(VIDEO_FILE).param("title", "방송")
				.header("X-Requested-With", "XMLHttpRequest"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.message").value("방송 등록에 실패했습니다. 잠시 후 다시 시도해 주세요."))
			.andExpect(content().string(not(containsString("connection refused"))));
	}

	@Test
	void databaseFailureShowsRegistrationPageForForm() throws Exception {
		mockMvc.perform(multipart("/broadcasts").file(VIDEO_FILE).param("title", "방송"))
			.andExpect(status().isInternalServerError())
			.andExpect(xpath("//form[@action='/broadcasts']").exists())
			.andExpect(content().string(containsString("방송 등록에 실패했습니다. 잠시 후 다시 시도해 주세요.")))
			.andExpect(content().string(not(containsString("connection refused"))));
	}

}
