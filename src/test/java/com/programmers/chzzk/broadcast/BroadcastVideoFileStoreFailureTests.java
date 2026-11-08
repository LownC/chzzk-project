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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BroadcastVideoFileStoreFailureTests {

	private static final MockMultipartFile VIDEO_FILE =
		new MockMultipartFile("videoFile", "video.mp4", "video/mp4", "fake mp4 bytes".getBytes(StandardCharsets.UTF_8));

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@MockitoBean
	private VideoFileStorage videoFileStorage;

	@BeforeEach
	void clearBroadcasts() {
		broadcastRepository.deleteAll();
	}

	@Test
	void videoFileStoreFailureReturnsServerErrorJsonForXhr() throws Exception {
		when(videoFileStorage.store(any())).thenThrow(new IOException("disk full at C:/secret/path"));

		mockMvc.perform(multipart("/broadcasts").file(VIDEO_FILE).param("title", "방송")
				.header("X-Requested-With", "XMLHttpRequest"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.message").value("영상 업로드에 실패했습니다. 잠시 후 다시 시도해 주세요."))
			.andExpect(content().string(not(containsString("secret"))));

		assertThat(broadcastRepository.count()).isZero();
	}

	@Test
	void videoFileStoreFailureShowsRegistrationPageForForm() throws Exception {
		when(videoFileStorage.store(any())).thenThrow(new IOException("disk full at C:/secret/path"));

		mockMvc.perform(multipart("/broadcasts").file(VIDEO_FILE).param("title", "방송"))
			.andExpect(status().isInternalServerError())
			.andExpect(xpath("//form[@action='/broadcasts']").exists())
			.andExpect(xpath("//input[@name='title']/@value").string("방송"))
			.andExpect(content().string(containsString("영상 업로드에 실패했습니다. 잠시 후 다시 시도해 주세요.")))
			.andExpect(content().string(not(containsString("secret"))));

		assertThat(broadcastRepository.count()).isZero();
	}

	@Test
	void unexpectedErrorDuringRegistrationReturnsGeneralRegistrationFailure() throws Exception {
		when(videoFileStorage.store(any())).thenThrow(new IllegalStateException("unexpected internal detail"));

		mockMvc.perform(multipart("/broadcasts").file(VIDEO_FILE).param("title", "방송")
				.header("X-Requested-With", "XMLHttpRequest"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.message").value("방송 등록에 실패했습니다. 잠시 후 다시 시도해 주세요."))
			.andExpect(content().string(not(containsString("internal detail"))));
	}

}
