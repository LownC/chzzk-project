package com.programmers.chzzk.live;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 휴대폰 송출 화면. 방송 시작·종료와 연결 협상은 화면의 스크립트가 /ws/live로 처리한다.
 * 시청 화면은 방송 상세(/broadcasts/{id})가 라이브 방송일 때 보여 준다.
 */
@Controller
public class LiveController {

	private final LiveProperties liveProperties;

	public LiveController(LiveProperties liveProperties) {
		this.liveProperties = liveProperties;
	}

	@GetMapping("/live/new")
	public String broadcasterPage(Model model) {
		model.addAttribute("iceServers", String.join(",", liveProperties.iceServers()));
		return "live-new";
	}

}
