package com.programmers.chzzk.broadcast;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * (⑥) 데스크톱 클라이언트용 읽기 전용 방송 목록. 화면 목록(/broadcasts)과 같은 서비스·정렬·공개 정책(GET은 공개)을 쓴다.
 * 엔티티 대신 필요한 값만 담은 응답을 돌려주며 소유자 등 회원 정보는 넣지 않는다.
 * 주소는 현재 요청 기준의 절대 주소라, 배포 환경에서는 프록시가 넘긴 https 주소가 된다(deploy 프로파일의 forward-headers).
 */
@RestController
public class BroadcastApiController {

	static final int PAGE_SIZE = 20;
	private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

	private final BroadcastService broadcastService;

	public BroadcastApiController(BroadcastService broadcastService) {
		this.broadcastService = broadcastService;
	}

	@GetMapping("/api/broadcasts")
	public BroadcastListResponse list(@RequestParam(defaultValue = "1") String page) {
		int requestedPage = parsePage(page);
		Page<Broadcast> broadcasts = broadcastService.findBroadcasts(
			PageRequest.of(requestedPage - 1, PAGE_SIZE, LIST_SORT));
		String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().toUriString();
		List<BroadcastSummary> summaries = broadcasts.getContent().stream()
			.map(broadcast -> BroadcastSummary.of(broadcast, baseUrl))
			.toList();
		return new BroadcastListResponse(summaries, requestedPage, broadcasts.getTotalPages(),
			broadcasts.getTotalElements());
	}

	private static int parsePage(String page) {
		try {
			int value = Integer.parseInt(page);
			if (value >= 1) {
				return value;
			}
		} catch (NumberFormatException ignored) {
			// 아래에서 잘못된 요청으로 처리한다.
		}
		throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
	}

	public record BroadcastListResponse(List<BroadcastSummary> broadcasts, int page, int totalPages,
		long totalElements) {
	}

	/**
	 * playbackUrl: 녹화방송의 MP4 주소(라이브는 null, 클라이언트는 재생 불가 안내). pageUrl: 웹 상세 화면.
	 * liveStatus: 라이브 방송만 LIVE 또는 ENDED.
	 */
	public record BroadcastSummary(Long id, String title, BroadcastType type, LiveStatus liveStatus,
		String playbackUrl, String pageUrl, LocalDateTime createdAt) {

		static BroadcastSummary of(Broadcast broadcast, String baseUrl) {
			String playbackUrl = broadcast.getType() == BroadcastType.RECORDED
				? baseUrl + "/videos/" + broadcast.getVideoFileName()
				: null;
			return new BroadcastSummary(broadcast.getId(), broadcast.getTitle(), broadcast.getType(),
				broadcast.getLiveStatus(), playbackUrl, baseUrl + "/broadcasts/" + broadcast.getId(),
				broadcast.getCreatedAt());
		}
	}

}
