package com.programmers.chzzk.live;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.programmers.chzzk.TestMembers;
import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.broadcast.BroadcastRepository;
import com.programmers.chzzk.member.Member;
import com.programmers.chzzk.member.MemberRepository;

@SpringBootTest(properties = "app.live.ice-servers=stun:stun.example.test:3478")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LivePagesTests {

	private static final String ITEMS = "//ul[@class='broadcast-list']/li";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@Autowired
	private MemberRepository memberRepository;

	private Member owner() {
		return TestMembers.save(memberRepository, "pages_owner");
	}

	@BeforeEach
	void clearBroadcasts() {
		broadcastRepository.deleteAll();
	}

	@Test
	void broadcasterPageHasCameraPreviewControlsAndScript() throws Exception {
		mockMvc.perform(get("/live/new").with(user(TestMembers.loginMember(owner()))))
			.andExpect(status().isOk())
			.andExpect(view().name("live-new"))
			.andExpect(xpath("//main//input[@id='title'][@maxlength='100']").exists())
			.andExpect(xpath("//main//video[@id='preview'][@muted][@playsinline][@autoplay]").exists())
			.andExpect(xpath("//main//button[@id='prepare-camera']").exists())
			.andExpect(xpath("//main//button[@id='start-live'][@disabled]").exists())
			.andExpect(xpath("//main//button[@id='end-live'][@disabled]").exists())
			.andExpect(xpath("//main//*[@class='live-status']").exists())
			.andExpect(xpath("//main//*[@class='live-error']").exists())
			.andExpect(xpath("//main[@id='live-broadcaster']/@data-ice-servers").string("stun:stun.example.test:3478"))
			.andExpect(xpath("//script[@src='/js/live-broadcaster.js']").exists());
	}

	@Test
	void headerLinksToLiveBroadcasterPage() throws Exception {
		mockMvc.perform(get("/broadcasts"))
			.andExpect(xpath("//header//a[@href='/live/new']").string("라이브 시작"));
	}

	@Test
	void listShowsLiveEndedAndRecordedStatus() throws Exception {
		LocalDateTime now = LocalDateTime.of(2026, 11, 18, 20, 0);
		broadcastRepository.save(new Broadcast("녹화 방송", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4"));
		Broadcast ended = Broadcast.startLive("끝난 라이브", owner(), now.plusMinutes(1));
		ended.endLive(now.plusMinutes(2));
		broadcastRepository.save(ended);
		broadcastRepository.save(Broadcast.startLive("진행 중 라이브", owner(), now.plusMinutes(3)));

		mockMvc.perform(get("/broadcasts"))
			.andExpect(status().isOk())
			.andExpect(xpath(ITEMS + "[a='진행 중 라이브']/span[contains(@class,'badge')]").string("LIVE"))
			.andExpect(xpath(ITEMS + "[a='끝난 라이브']/span[contains(@class,'badge')]").string("종료"))
			.andExpect(xpath(ITEMS + "[a='녹화 방송']/span[contains(@class,'badge')]").string("녹화"));
	}

	@Test
	void liveDetailShowsViewerPlayerWithBroadcastId() throws Exception {
		Long id = broadcastRepository.save(Broadcast.startLive("진행 중 라이브", owner(), LocalDateTime.now())).getId();

		mockMvc.perform(get("/broadcasts/" + id))
			.andExpect(status().isOk())
			.andExpect(view().name("live-watch"))
			.andExpect(xpath("//main/h1").string("진행 중 라이브"))
			.andExpect(xpath("//main[@id='live-viewer']/@data-broadcast-id").string(String.valueOf(id)))
			.andExpect(xpath("//main[@id='live-viewer']/@data-ice-servers").string("stun:stun.example.test:3478"))
			.andExpect(xpath("//main//*[@class='live-state']").string("방송 중"))
			.andExpect(xpath("//main//video[@id='live-video'][@playsinline][@autoplay]").exists())
			.andExpect(xpath("//main//button[@id='unmute']").exists())
			.andExpect(xpath("//main//*[@class='live-error']").exists())
			.andExpect(xpath("//script[@src='/js/live-viewer.js']").exists());
	}

	@Test
	void endedLiveDetailShowsEndedNoticeWithoutPlayer() throws Exception {
		Broadcast ended = Broadcast.startLive("끝난 라이브", owner(), LocalDateTime.now().minusMinutes(10));
		ended.endLive(LocalDateTime.now().minusMinutes(1));
		Long id = broadcastRepository.save(ended).getId();

		mockMvc.perform(get("/broadcasts/" + id))
			.andExpect(status().isOk())
			.andExpect(xpath("//main//*[@class='live-state']").string("방송 종료"))
			.andExpect(content().string(containsString("방송이 종료되었습니다.")))
			.andExpect(xpath("//video").doesNotExist())
			.andExpect(xpath("//script[@src='/js/live-viewer.js']").doesNotExist());
	}

	@Test
	void broadcasterAndViewerScriptsAreServed() throws Exception {
		mockMvc.perform(get("/js/live-broadcaster.js"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("getUserMedia")))
			.andExpect(content().string(containsString("RTCPeerConnection")));
		mockMvc.perform(get("/js/live-viewer.js"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("RTCPeerConnection")));
	}

}
