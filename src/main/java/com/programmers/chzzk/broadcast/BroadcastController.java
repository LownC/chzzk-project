package com.programmers.chzzk.broadcast;

import java.util.Optional;
import java.util.stream.IntStream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.programmers.chzzk.comment.CommentService;
import com.programmers.chzzk.donation.DonationService;
import com.programmers.chzzk.global.exception.NotFoundException;
import com.programmers.chzzk.live.LiveProperties;
import com.programmers.chzzk.member.LoginMember;

@Controller
public class BroadcastController {

	private static final int PAGE_SIZE = 10;
	private static final int PAGE_GROUP_SIZE = 10;
	private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

	private final BroadcastService broadcastService;
	private final CommentService commentService;
	private final DonationService donationService;
	private final LiveProperties liveProperties;

	public BroadcastController(BroadcastService broadcastService, CommentService commentService,
		DonationService donationService, LiveProperties liveProperties) {
		this.broadcastService = broadcastService;
		this.commentService = commentService;
		this.donationService = donationService;
		this.liveProperties = liveProperties;
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
	 * 라이브 방송은 영상 파일 대신 실시간 시청 화면(방송 중) 또는 종료 안내를 보여 준다.
	 * 누구나 볼 수 있고, 댓글 목록도 함께 보여 준다. 수정·삭제·종료 버튼은 소유자에게만 보이지만
	 * 권한 검사는 버튼과 상관없이 각 요청을 처리하는 서버가 한다.
	 */
	@GetMapping("/broadcasts/{id}")
	public String detail(@PathVariable String id, @AuthenticationPrincipal LoginMember loginMember, Model model) {
		Broadcast broadcast = parseBroadcastId(id)
			.flatMap(broadcastService::findBroadcast)
			.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
		model.addAttribute("broadcast", broadcast);
		model.addAttribute("isOwner", loginMember != null && broadcast.isOwnedBy(loginMember.getId()));
		model.addAttribute("comments", commentService.findComments(broadcast.getId()));
		model.addAttribute("donations", donationService.findDonations(broadcast.getId()));
		model.addAttribute("donationTotal", donationService.totalAmount(broadcast.getId()));
		if (broadcast.getType() == BroadcastType.LIVE) {
			model.addAttribute("iceServers", String.join(",", liveProperties.iceServers()));
			return "live-watch";
		}
		return "detail";
	}

	@PostMapping(value = "/broadcasts", headers = "X-Requested-With=XMLHttpRequest")
	@ResponseBody
	public ResponseEntity<RegistrationResponse> registerByXhr(
		@RequestParam(required = false) String title,
		@RequestParam(required = false) MultipartFile videoFile,
		@AuthenticationPrincipal LoginMember loginMember
	) {
		Long broadcastId = broadcastService.register(title, videoFile, loginMember.getId());
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(new RegistrationResponse(broadcastId, detailUrl(broadcastId)));
	}

	@PostMapping("/broadcasts")
	public String registerByForm(
		@RequestParam(required = false) String title,
		@RequestParam(required = false) MultipartFile videoFile,
		@AuthenticationPrincipal LoginMember loginMember
	) {
		Long broadcastId = broadcastService.register(title, videoFile, loginMember.getId());
		return "redirect:" + detailUrl(broadcastId);
	}

	/**
	 * 제목 수정 화면. 소유자가 아니면 403이다.
	 */
	@GetMapping("/broadcasts/{id}/edit")
	public String editForm(@PathVariable String id, @AuthenticationPrincipal LoginMember loginMember, Model model) {
		Broadcast broadcast = broadcastService.findOwnBroadcast(requireBroadcastId(id), loginMember.getId());
		model.addAttribute("broadcast", broadcast);
		model.addAttribute("title", broadcast.getTitle());
		return "edit";
	}

	@PostMapping("/broadcasts/{id}/edit")
	public ModelAndView edit(@PathVariable String id, @RequestParam(required = false) String title,
		@AuthenticationPrincipal LoginMember loginMember) {
		long broadcastId = requireBroadcastId(id);
		try {
			broadcastService.changeTitle(broadcastId, loginMember.getId(), title);
		} catch (BroadcastRegistrationException e) {
			ModelAndView editPage = new ModelAndView("edit", e.getError().status());
			editPage.addObject("broadcast", broadcastService.findOwnBroadcast(broadcastId, loginMember.getId()));
			editPage.addObject("title", title);
			editPage.addObject("errorMessage", e.getError().message());
			return editPage;
		}
		return new ModelAndView("redirect:" + detailUrl(broadcastId));
	}

	/**
	 * 소유자만 삭제할 수 있다. 방송 중인 라이브는 상세 화면에 안내를 보여 주고 지우지 않는다.
	 */
	@PostMapping("/broadcasts/{id}/delete")
	public String delete(@PathVariable String id, @AuthenticationPrincipal LoginMember loginMember,
		RedirectAttributes redirectAttributes) {
		long broadcastId = requireBroadcastId(id);
		try {
			broadcastService.delete(broadcastId, loginMember.getId());
		} catch (BroadcastStillLiveException | BroadcastHasDonationsException e) {
			redirectAttributes.addFlashAttribute("manageError", e.getMessage());
			return "redirect:" + detailUrl(broadcastId);
		}
		return "redirect:/broadcasts";
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

	private static long requireBroadcastId(String id) {
		return parseBroadcastId(id).orElseThrow(() -> new NotFoundException("방송 ID 형식 오류: " + id));
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
