package com.programmers.chzzk.broadcast;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 등록과 무관한 요청에서 예상하지 못한 예외가 나면 일반 오류 화면을 보여준다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnexpectedErrorPageTests {

	private static final String INTERNAL_DETAIL = "internal secret detail";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private BroadcastRepository broadcastRepository;

	@Test
	void listQueryFailureShowsErrorPage() throws Exception {
		given(broadcastRepository.findAll(any(Pageable.class))).willThrow(new IllegalStateException(INTERNAL_DETAIL));

		expectErrorPage(mockMvc.perform(get("/broadcasts")));
	}

	@Test
	void detailQueryFailureShowsErrorPage() throws Exception {
		given(broadcastRepository.findById(anyLong())).willThrow(new IllegalStateException(INTERNAL_DETAIL));

		expectErrorPage(mockMvc.perform(get("/broadcasts/1")));
	}

	private static void expectErrorPage(ResultActions result) throws Exception {
		result.andExpect(status().isInternalServerError())
			.andExpect(xpath("//main").string(containsString("일시적인 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.")))
			.andExpect(xpath("//main//a[@href='/broadcasts']").exists())
			.andExpect(xpath("//header//a[@href='/broadcasts/new']").exists())
			.andExpect(xpath("//main").string(not(containsString("500"))))
			.andExpect(content().string(not(containsString(INTERNAL_DETAIL))))
			.andExpect(content().string(not(containsString("Exception"))));
	}

}
