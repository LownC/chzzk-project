package com.programmers.chzzk.live;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import com.programmers.chzzk.global.exception.NotFoundException;
import com.programmers.chzzk.member.LoginMember;

/**
 * 휴대폰 송출 화면(로그인 필요). 방송 시작·종료와 연결 협상은 화면의 스크립트가 /ws/live로 처리한다.
 * 시청 화면은 방송 상세(/broadcasts/{id})가 라이브 방송일 때 보여 준다.
 * 소유자는 송출 화면을 닫지 않고도 방송 상세 화면에서 방송을 종료할 수 있다.
 */
@Controller
public class LiveController {

	private final LiveProperties liveProperties;
	private final LiveBroadcastService liveBroadcastService;
	private final LiveSignalingHandler liveSignalingHandler;

	public LiveController(LiveProperties liveProperties, LiveBroadcastService liveBroadcastService,
		LiveSignalingHandler liveSignalingHandler) {
		this.liveProperties = liveProperties;
		this.liveBroadcastService = liveBroadcastService;
		this.liveSignalingHandler = liveSignalingHandler;
	}

	@GetMapping("/live/new")
	public String broadcasterPage(Model model) {
		model.addAttribute("iceServers", String.join(",", liveProperties.iceServers()));
		return "live-new";
	}

	/**
	 * 소유자만 종료할 수 있다. DB 종료가 커밋된 뒤 송출자·시청자 연결에 종료를 알리고 닫는다.
	 */
	@PostMapping("/broadcasts/{id}/end")
	public String endByOwner(@PathVariable String id, @AuthenticationPrincipal LoginMember loginMember) {
		long broadcastId = parseId(id);
		liveBroadcastService.endByOwner(broadcastId, loginMember.getId());
		liveSignalingHandler.closeEndedBroadcast(broadcastId);
		return "redirect:/broadcasts/" + broadcastId;
	}

	private static long parseId(String id) {
		try {
			long parsedId = Long.parseLong(id);
			if (parsedId > 0) {
				return parsedId;
			}
		} catch (NumberFormatException e) {
			// 아래에서 방송 없음으로 응답한다.
		}
		throw new NotFoundException("방송 ID 형식 오류: " + id);
	}

}
