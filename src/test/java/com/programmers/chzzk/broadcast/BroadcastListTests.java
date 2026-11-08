package com.programmers.chzzk.broadcast;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BroadcastListTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@BeforeEach
	@AfterEach
	void clearBroadcasts() {
		broadcastRepository.deleteAll();
	}

	@Test
	void rootRedirectsToBroadcastList() throws Exception {
		mockMvc.perform(get("/"))
			.andExpect(status().isFound())
			.andExpect(redirectedUrl("/broadcasts"));
	}

	@Test
	void emptyListShowsGuideAndRegisterLink() throws Exception {
		mockMvc.perform(get("/broadcasts"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("등록된 방송이 없습니다. 첫 번째 방송을 등록해 보세요.")))
			.andExpect(xpath("//main//a[@href='/broadcasts/new']").exists())
			.andExpect(content().string(not(containsString("page="))));
	}

	@Test
	void listWithBroadcastDoesNotShowEmptyGuide() throws Exception {
		broadcastRepository.save(new Broadcast("첫 방송", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4"));

		mockMvc.perform(get("/broadcasts"))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("등록된 방송이 없습니다."))));
	}

	@Test
	void listPageHasCommonHeader() throws Exception {
		mockMvc.perform(get("/broadcasts"))
			.andExpect(status().isOk())
			.andExpect(xpath("//header").string(containsString("치지직")))
			.andExpect(xpath("//header//a[@href='/broadcasts']").exists())
			.andExpect(xpath("//header//a[@href='/broadcasts/new']").exists());
	}

}
