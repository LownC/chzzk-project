package com.programmers.chzzk.broadcast;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 영상 파일은 만들지 않는다. 파일이 없는 방송도 상세 화면은 정상 표시되어야 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BroadcastDetailTests {

	private static final String VIDEO_FILE_NAME = "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4";
	private static final String NOT_FOUND_MESSAGE = "요청한 페이지를 찾을 수 없습니다.";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	@AfterEach
	void clearBroadcasts() {
		broadcastRepository.deleteAll();
	}

	@Test
	void detailShowsTitleCreatedAtAndPlayerWithoutFileName() throws Exception {
		Long id = insertBroadcast("상세 방송", LocalDateTime.of(2026, 3, 4, 15, 6, 7));

		mockMvc.perform(get("/broadcasts/" + id))
			.andExpect(status().isOk())
			.andExpect(xpath("//main/h1").string("상세 방송"))
			.andExpect(xpath("//main//time").string("2026.03.04 15:06"))
			.andExpect(xpath("//video/@src").string("/videos/" + VIDEO_FILE_NAME))
			.andExpect(xpath("//video/@controls").exists())
			.andExpect(xpath("//video/@autoplay").doesNotExist())
			.andExpect(xpath("//main").string(not(containsString(VIDEO_FILE_NAME))))
			.andExpect(xpath("//header//a[@href='/broadcasts']").exists());
	}

	@Test
	void detailWithoutVideoFileStillShowsPlaybackFailureArea() throws Exception {
		Long id = insertBroadcast("파일 없는 방송", LocalDateTime.of(2026, 3, 4, 15, 6, 7));

		mockMvc.perform(get("/broadcasts/" + id))
			.andExpect(status().isOk())
			.andExpect(xpath("//*[@class='video-error']").string("영상을 재생할 수 없습니다."))
			.andExpect(xpath("//*[@class='video-error']/@hidden").exists());
	}

	@Test
	void titleIsEscaped() throws Exception {
		Long id = insertBroadcast("<script>alert(1)</script>", LocalDateTime.of(2026, 3, 4, 15, 6, 7));

		mockMvc.perform(get("/broadcasts/" + id))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
			.andExpect(content().string(not(containsString("<script>alert(1)</script>"))));
	}

	@ParameterizedTest
	@ValueSource(strings = {"abc", "0", "-1", "99999999999999999999", "999999"})
	void invalidOrMissingIdShowsNotFoundPage(String id) throws Exception {
		expectNotFoundPage(mockMvc.perform(get("/broadcasts/" + id)));
	}

	@Test
	void unknownPathShowsNotFoundPage() throws Exception {
		expectNotFoundPage(mockMvc.perform(get("/no-such-page")));
	}

	private static void expectNotFoundPage(ResultActions result) throws Exception {
		result.andExpect(status().isNotFound())
			.andExpect(xpath("//main").string(containsString(NOT_FOUND_MESSAGE)))
			.andExpect(xpath("//main//a[@href='/broadcasts']").exists())
			.andExpect(xpath("//header//a[@href='/broadcasts/new']").exists())
			.andExpect(xpath("//main").string(not(containsString("404"))))
			.andExpect(content().string(not(containsString("Exception"))));
	}

	private Long insertBroadcast(String title, LocalDateTime createdAt) {
		jdbcTemplate.update("insert into broadcast (title, video_file_name, created_at) values (?, ?, ?)",
			title, VIDEO_FILE_NAME, createdAt);
		return broadcastRepository.findAll().getFirst().getId();
	}

}
