package com.programmers.chzzk.broadcast;

import java.util.Optional;
import java.util.stream.IntStream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Controller
public class BroadcastController {

	private static final int PAGE_SIZE = 10;
	private static final int PAGE_GROUP_SIZE = 10;
	private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

	private final BroadcastService broadcastService;

	public BroadcastController(BroadcastService broadcastService) {
		this.broadcastService = broadcastService;
	}

	@GetMapping("/")
	public String root() {
		return "redirect:/broadcasts";
	}

	@GetMapping("/broadcasts")
	public String list(@RequestParam(required = false) String page, Model model) {
		int requestedPage = parsePage(page);
		Page<Broadcast> broadcasts = broadcastService.findBroadcasts(
			PageRequest.of(requestedPage - 1, PAGE_SIZE, LIST_SORT));
		model.addAttribute("noBroadcasts", broadcasts.getTotalElements() == 0);
		model.addAttribute("broadcasts", broadcasts.getContent());
		addPageGroup(model, requestedPage, broadcasts.getTotalPages());
		return "list";
	}

	@GetMapping("/broadcasts/new")
	public String registrationForm() {
		return "new";
	}

	/**
	 * 숫자가 아님, Long 범위 초과, 0 이하, 없는 ID는 모두 방송 없음(404)이다.
	 */
	@GetMapping("/broadcasts/{id}")
	public String detail(@PathVariable String id, Model model) {
		Broadcast broadcast = parseBroadcastId(id)
			.flatMap(broadcastService::findBroadcast)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		model.addAttribute("broadcast", broadcast);
		return "detail";
	}

	@PostMapping(value = "/broadcasts", headers = "X-Requested-With=XMLHttpRequest")
	@ResponseBody
	public ResponseEntity<RegistrationResponse> registerByXhr(
		@RequestParam(required = false) String title,
		@RequestParam(required = false) MultipartFile videoFile
	) {
		Long broadcastId = broadcastService.register(title, videoFile);
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(new RegistrationResponse(broadcastId, detailUrl(broadcastId)));
	}

	@PostMapping("/broadcasts")
	public String registerByForm(
		@RequestParam(required = false) String title,
		@RequestParam(required = false) MultipartFile videoFile
	) {
		Long broadcastId = broadcastService.register(title, videoFile);
		return "redirect:" + detailUrl(broadcastId);
	}

	/**
	 * 없음, 빈 값, 숫자가 아님, int 범위 초과, 1 미만은 모두 1페이지로 본다.
	 */
	private static int parsePage(String page) {
		if (page == null) {
			return 1;
		}
		try {
			return Math.max(Integer.parseInt(page), 1);
		} catch (NumberFormatException e) {
			return 1;
		}
	}

	private static Optional<Long> parseBroadcastId(String id) {
		try {
			long parsedId = Long.parseLong(id);
			return parsedId > 0 ? Optional.of(parsedId) : Optional.empty();
		} catch (NumberFormatException e) {
			return Optional.empty();
		}
	}

	/**
	 * 요청 페이지(범위를 넘으면 마지막 페이지)가 속한 10개 묶음을 만든다.
	 * 요청 페이지가 실제로 있을 때만 현재 페이지로 강조한다.
	 */
	private static void addPageGroup(Model model, int requestedPage, int totalPages) {
		int basePage = Math.min(requestedPage, totalPages);
		int groupStart = (basePage - 1) / PAGE_GROUP_SIZE * PAGE_GROUP_SIZE + 1;
		int groupEnd = Math.min(groupStart + PAGE_GROUP_SIZE - 1, totalPages);
		model.addAttribute("pageNumbers", IntStream.rangeClosed(groupStart, groupEnd).boxed().toList());
		model.addAttribute("currentPage", requestedPage <= totalPages ? requestedPage : null);
		model.addAttribute("previousGroupPage", groupStart > 1 ? groupStart - 1 : null);
		model.addAttribute("nextGroupPage", groupEnd < totalPages ? groupEnd + 1 : null);
	}

	private static String detailUrl(Long broadcastId) {
		return "/broadcasts/" + broadcastId;
	}

	record RegistrationResponse(Long broadcastId, String redirectUrl) {
	}

}
