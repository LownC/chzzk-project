package com.programmers.chzzk.broadcast;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import java.time.LocalDateTime;
import java.util.UUID;

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

/**
 * 등록 시각을 직접 정하기 위해 방송을 SQL로 저장한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BroadcastPaginationTests {

	private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
	private static final String ITEMS = "//ul[@class='broadcast-list']/li";
	private static final String PAGE_NUMBERS = "//nav[@class='pagination']/a[@class='page-number']";
	private static final String CURRENT_PAGE = "//nav[@class='pagination']/a[@aria-current='page']";
	private static final String PREVIOUS_GROUP = "//nav[@class='pagination']/a[@class='previous-group']";
	private static final String NEXT_GROUP = "//nav[@class='pagination']/a[@class='next-group']";

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
	void firstPageShowsTenNewestBroadcasts() throws Exception {
		insertBroadcasts(23);

		mockMvc.perform(get("/broadcasts"))
			.andExpect(status().isOk())
			.andExpect(xpath(ITEMS).nodeCount(10))
			.andExpect(xpath(ITEMS + "[1]/a").string("방송 23"))
			.andExpect(xpath(ITEMS + "[10]/a").string("방송 14"));
	}

	@Test
	void lastPageShowsRemainingBroadcasts() throws Exception {
		insertBroadcasts(23);

		mockMvc.perform(get("/broadcasts").param("page", "3"))
			.andExpect(status().isOk())
			.andExpect(xpath(ITEMS).nodeCount(3))
			.andExpect(xpath(ITEMS + "[1]/a").string("방송 03"))
			.andExpect(xpath(ITEMS + "[3]/a").string("방송 01"));
	}

	@Test
	void broadcastsWithSameCreatedAtAreOrderedByIdDescending() throws Exception {
		insertBroadcast("먼저 저장, 최신", BASE_TIME.plusHours(1));
		insertBroadcast("오래된 방송", BASE_TIME);
		insertBroadcast("나중 저장, 최신", BASE_TIME.plusHours(1));

		mockMvc.perform(get("/broadcasts"))
			.andExpect(xpath(ITEMS + "[1]/a").string("나중 저장, 최신"))
			.andExpect(xpath(ITEMS + "[2]/a").string("먼저 저장, 최신"))
			.andExpect(xpath(ITEMS + "[3]/a").string("오래된 방송"));
	}

	@Test
	void itemShowsCreatedAtAndLinksToDetail() throws Exception {
		insertBroadcast("날짜 확인", BASE_TIME);
		Long id = broadcastRepository.findAll().getFirst().getId();

		mockMvc.perform(get("/broadcasts"))
			.andExpect(xpath(ITEMS + "[1]/a/@href").string("/broadcasts/" + id))
			.andExpect(xpath(ITEMS + "[1]/time").string("2026.01.02 03:04"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "-1", "abc", "", "99999999999999999999"})
	void invalidPageShowsFirstPage(String page) throws Exception {
		insertBroadcasts(23);

		mockMvc.perform(get("/broadcasts").param("page", page))
			.andExpect(status().isOk())
			.andExpect(xpath(ITEMS).nodeCount(10))
			.andExpect(xpath(ITEMS + "[1]/a").string("방송 23"));
	}

	@Test
	void sizeParameterIsIgnored() throws Exception {
		insertBroadcasts(23);

		mockMvc.perform(get("/broadcasts").param("size", "50"))
			.andExpect(status().isOk())
			.andExpect(xpath(ITEMS).nodeCount(10));
	}

	@Test
	void pageBeyondQueryableOffsetShowsEmptyListWithoutError() throws Exception {
		insertBroadcasts(23);

		mockMvc.perform(get("/broadcasts").param("page", String.valueOf(Integer.MAX_VALUE)))
			.andExpect(status().isOk())
			.andExpect(xpath(ITEMS).nodeCount(0));
	}

	@Test
	void pageNumbersLinkToEachPageAndHighlightCurrentPage() throws Exception {
		insertBroadcasts(23);

		mockMvc.perform(get("/broadcasts").param("page", "2").param("size", "50"))
			.andExpect(xpath(PAGE_NUMBERS).nodeCount(3))
			.andExpect(xpath(PAGE_NUMBERS + "[1]/@href").string("/broadcasts?page=1"))
			.andExpect(xpath(PAGE_NUMBERS + "[3]/@href").string("/broadcasts?page=3"))
			.andExpect(xpath(CURRENT_PAGE).nodeCount(1))
			.andExpect(xpath(CURRENT_PAGE).string("2"))
			.andExpect(xpath(PREVIOUS_GROUP).doesNotExist())
			.andExpect(xpath(NEXT_GROUP).doesNotExist());
	}

	@Test
	void pageBeyondLastPageShowsEmptyListWithLastGroupAndNoHighlight() throws Exception {
		insertBroadcasts(23);

		mockMvc.perform(get("/broadcasts").param("page", "57"))
			.andExpect(status().isOk())
			.andExpect(xpath(ITEMS).nodeCount(0))
			.andExpect(content().string(not(containsString("등록된 방송이 없습니다."))))
			.andExpect(xpath(PAGE_NUMBERS).nodeCount(3))
			.andExpect(xpath(PAGE_NUMBERS + "[1]").string("1"))
			.andExpect(xpath(PAGE_NUMBERS + "[3]").string("3"))
			.andExpect(xpath(CURRENT_PAGE).doesNotExist());
	}

	@Test
	void firstGroupShowsTenPagesAndNextGroupLink() throws Exception {
		insertBroadcasts(101);

		mockMvc.perform(get("/broadcasts"))
			.andExpect(xpath(PAGE_NUMBERS).nodeCount(10))
			.andExpect(xpath(PAGE_NUMBERS + "[1]").string("1"))
			.andExpect(xpath(PAGE_NUMBERS + "[10]").string("10"))
			.andExpect(xpath(PREVIOUS_GROUP).doesNotExist())
			.andExpect(xpath(NEXT_GROUP + "/@href").string("/broadcasts?page=11"));
	}

	@Test
	void secondGroupShowsPreviousGroupLink() throws Exception {
		insertBroadcasts(101);

		mockMvc.perform(get("/broadcasts").param("page", "11"))
			.andExpect(xpath(ITEMS).nodeCount(1))
			.andExpect(xpath(PAGE_NUMBERS).nodeCount(1))
			.andExpect(xpath(CURRENT_PAGE).string("11"))
			.andExpect(xpath(PREVIOUS_GROUP + "/@href").string("/broadcasts?page=10"))
			.andExpect(xpath(NEXT_GROUP).doesNotExist());
	}

	/**
	 * "방송 01"부터 1분 간격으로 저장한다. 번호가 클수록 최신이다.
	 */
	private void insertBroadcasts(int count) {
		for (int number = 1; number <= count; number++) {
			insertBroadcast("방송 %02d".formatted(number), BASE_TIME.plusMinutes(number));
		}
	}

	private void insertBroadcast(String title, LocalDateTime createdAt) {
		jdbcTemplate.update("insert into broadcast (title, video_file_name, created_at) values (?, ?, ?)",
			title, UUID.randomUUID() + ".mp4", createdAt);
	}

}
