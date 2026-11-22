package com.programmers.chzzk.broadcast;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.programmers.chzzk.TestMembers;
import com.programmers.chzzk.comment.CommentRepository;
import com.programmers.chzzk.member.Member;
import com.programmers.chzzk.member.MemberRepository;

/**
 * (⑥) 데스크톱 클라이언트용 방송 목록 API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BroadcastApiTests {

	private static final String VIDEO_FILE_NAME = "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@Autowired
	private CommentRepository commentRepository;

	@Autowired
	private MemberRepository memberRepository;

	private Member owner;

	@BeforeEach
	void setUp() {
		commentRepository.deleteAll();
		broadcastRepository.deleteAll();
		owner = TestMembers.save(memberRepository, "api_owner");
	}

	@AfterEach
	void cleanUp() {
		commentRepository.deleteAll();
		broadcastRepository.deleteAll();
	}

	@Test
	void emptyListIsPublicAndReturnsNoBroadcasts() throws Exception {
		mockMvc.perform(get("/api/broadcasts"))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(jsonPath("$.broadcasts", hasSize(0)))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void recordedBroadcastHasAbsolutePlaybackUrlAndLiveBroadcastHasNone() throws Exception {
		Broadcast recorded = broadcastRepository.save(new Broadcast("녹화 방송", VIDEO_FILE_NAME, owner));
		Broadcast live = broadcastRepository.save(Broadcast.startLive("라이브 방송", owner, LocalDateTime.now()));

		mockMvc.perform(get("/api/broadcasts"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.broadcasts", hasSize(2)))
			.andExpect(jsonPath("$.broadcasts[?(@.id == %d)].type", recorded.getId()).value("RECORDED"))
			.andExpect(jsonPath("$.broadcasts[?(@.id == %d)].playbackUrl", recorded.getId())
				.value("http://localhost/videos/" + VIDEO_FILE_NAME))
			.andExpect(jsonPath("$.broadcasts[?(@.id == %d)].pageUrl", recorded.getId())
				.value("http://localhost/broadcasts/" + recorded.getId()))
			.andExpect(jsonPath("$.broadcasts[?(@.id == %d)].type", live.getId()).value("LIVE"))
			.andExpect(jsonPath("$.broadcasts[?(@.id == %d)].liveStatus", live.getId()).value("LIVE"))
			.andExpect(jsonPath("$.broadcasts[?(@.id == %d)].playbackUrl", live.getId()).value((Object) null));
	}

	@Test
	void responseDoesNotExposeOwnerOrInternalFields() throws Exception {
		broadcastRepository.save(new Broadcast("녹화 방송", VIDEO_FILE_NAME, owner));

		mockMvc.perform(get("/api/broadcasts"))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("api_owner"))))
			.andExpect(content().string(not(containsString("owner"))))
			.andExpect(content().string(not(containsString("password"))))
			.andExpect(content().string(not(containsString("lastHeartbeatAt"))));
	}

	@Test
	void forwardedProtoAndHostAreNotTrustedWithoutTheDeployProxySetting() throws Exception {
		broadcastRepository.save(new Broadcast("녹화 방송", VIDEO_FILE_NAME, owner));

		// 테스트·개발 프로파일은 전달 헤더를 쓰지 않는다. 배포 프로파일의 신뢰 처리는 DeployProxyServerTests에서 확인한다.
		mockMvc.perform(get("/api/broadcasts")
				.header("X-Forwarded-Proto", "https")
				.header("X-Forwarded-Host", "attacker.example"))
			.andExpect(status().isOk())
			.andExpect(content().string(not(containsString("attacker.example"))));
	}

	@Test
	void invalidPageIsBadRequest() throws Exception {
		mockMvc.perform(get("/api/broadcasts").param("page", "0")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/broadcasts").param("page", "abc")).andExpect(status().isBadRequest());
	}

	@Test
	void pagesHoldTwentyBroadcasts() throws Exception {
		for (int i = 0; i < BroadcastApiController.PAGE_SIZE + 1; i++) {
			broadcastRepository.save(new Broadcast("방송 " + i, VIDEO_FILE_NAME, owner));
		}

		mockMvc.perform(get("/api/broadcasts"))
			.andExpect(jsonPath("$.broadcasts", hasSize(BroadcastApiController.PAGE_SIZE)))
			.andExpect(jsonPath("$.totalPages").value(2));
		mockMvc.perform(get("/api/broadcasts").param("page", "2"))
			.andExpect(jsonPath("$.broadcasts", hasSize(1)));
	}

}
