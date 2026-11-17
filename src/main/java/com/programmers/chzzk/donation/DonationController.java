package com.programmers.chzzk.donation;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

import com.programmers.chzzk.global.exception.NotFoundException;
import com.programmers.chzzk.member.LoginMember;
import com.programmers.chzzk.payment.FakePaymentGateway;
import com.programmers.chzzk.payment.PaymentGateway;
import com.programmers.chzzk.payment.PaymentProperties;

/**
 * 후원 화면 흐름. 모든 요청은 로그인이 필요하고(보안 설정), 주문은 주문한 회원만 다룰 수 있다(서비스).
 * <ol>
 *     <li>{@code POST /broadcasts/{id}/donations}: 금액·메시지로 주문 생성 → 결제 화면</li>
 *     <li>{@code GET /donations/{orderId}/checkout}: 토스 결제창(테스트 모드) 또는 가짜 결제창</li>
 *     <li>{@code GET /donations/success}·{@code /donations/fail}: 결제창이 돌려보내는 주소. 서버가 승인·검증 후 결과 화면으로 보낸다.</li>
 *     <li>{@code GET /donations/{orderId}}: 결과 화면(확정·실패·취소). 새로고침해도 다시 승인하지 않는다.</li>
 * </ol>
 */
@Controller
public class DonationController {

	static final String DONATION_ERROR = "donationError";

	private final DonationService donationService;
	private final PaymentGateway paymentGateway;
	private final PaymentProperties paymentProperties;

	public DonationController(DonationService donationService, PaymentGateway paymentGateway,
		PaymentProperties paymentProperties) {
		this.donationService = donationService;
		this.paymentGateway = paymentGateway;
		this.paymentProperties = paymentProperties;
	}

	@PostMapping("/broadcasts/{broadcastId}/donations")
	public String prepare(@PathVariable String broadcastId, @RequestParam(required = false) String amount,
		@RequestParam(required = false) String message, @AuthenticationPrincipal LoginMember loginMember,
		RedirectAttributes redirectAttributes) {
		long id = parseBroadcastId(broadcastId);
		String orderId;
		try {
			orderId = donationService.prepare(id, loginMember.getId(), amount, message);
		} catch (DonationException e) {
			redirectAttributes.addFlashAttribute(DONATION_ERROR, e.getMessage());
			return "redirect:/broadcasts/" + id + "#donations";
		}
		return "redirect:/donations/" + orderId + "/checkout";
	}

	@GetMapping("/donations/{orderId}/checkout")
	public String checkout(@PathVariable String orderId, @AuthenticationPrincipal LoginMember loginMember, Model model) {
		Donation donation = donationService.findOwnOrder(orderId, loginMember.getId());
		if (donation.getStatus() != DonationStatus.READY) {
			return "redirect:/donations/" + donation.getOrderId();
		}
		String baseUrl = ServletUriComponentsBuilder.fromCurrentContextPath().toUriString();
		model.addAttribute("donation", donation);
		model.addAttribute("gateway", paymentGateway.name());
		model.addAttribute("tossClientKey", paymentProperties.toss().clientKey());
		model.addAttribute("customerKey", "member-" + loginMember.getId());
		model.addAttribute("orderName", orderName(donation));
		model.addAttribute("successUrl", baseUrl + "/donations/success");
		model.addAttribute("failUrl", baseUrl + "/donations/fail");
		return "donation-checkout";
	}

	/**
	 * 가짜 결제창의 버튼(외부 호출 없음). 토스 결제창이 하는 것처럼 성공·실패 주소로 돌려보낸다.
	 * {@code approve}: 인증 성공, {@code decline}: 인증은 됐지만 승인 단계에서 카드사 거절, {@code fail}: 결제창 실패,
	 * {@code cancel}: 사용자가 결제창을 닫음. 가짜 게이트웨이를 쓰지 않을 때는 없는 주소(404)다.
	 */
	@PostMapping("/donations/{orderId}/fake-pay")
	public String fakePay(@PathVariable String orderId, @RequestParam(required = false) String result,
		@AuthenticationPrincipal LoginMember loginMember) {
		if (!(paymentGateway instanceof FakePaymentGateway fakeGateway)) {
			throw new NotFoundException("가짜 결제창은 fake 게이트웨이에서만 쓴다");
		}
		Donation donation = donationService.findOwnOrder(orderId, loginMember.getId());
		String id = donation.getOrderId();
		return switch (result == null ? "" : result) {
			case "approve" -> successRedirect(fakeGateway.authorize(id, donation.getAmount(), false), donation);
			case "decline" -> successRedirect(fakeGateway.authorize(id, donation.getAmount(), true), donation);
			case "fail" -> failRedirect(FakePaymentGateway.REJECT_CARD_COMPANY, "카드사에서 결제를 거절했습니다.", id);
			case "cancel" -> failRedirect(DonationService.USER_CANCELED, "사용자가 결제를 취소했습니다.", id);
			default -> "redirect:/donations/" + id + "/checkout";
		};
	}

	/**
	 * 결제창 인증 성공 뒤 돌아오는 주소. 쿼리의 금액·결제 키는 위조될 수 있으므로 서버가 주문과 비교하고 승인을 요청한다.
	 */
	@GetMapping("/donations/success")
	public String success(@RequestParam(required = false) String paymentKey, @RequestParam(required = false) String orderId,
		@RequestParam(required = false) String amount, @AuthenticationPrincipal LoginMember loginMember) {
		String id = requireOrderId(orderId);
		donationService.confirm(id, paymentKey, amount, loginMember.getId());
		return "redirect:/donations/" + donationService.findOwnOrder(id, loginMember.getId()).getOrderId();
	}

	@GetMapping("/donations/fail")
	public String fail(@RequestParam(required = false) String code, @RequestParam(required = false) String orderId,
		@AuthenticationPrincipal LoginMember loginMember) {
		String id = requireOrderId(orderId);
		donationService.fail(id, code, loginMember.getId());
		return "redirect:/donations/" + donationService.findOwnOrder(id, loginMember.getId()).getOrderId();
	}

	@GetMapping("/donations/{orderId}")
	public String result(@PathVariable String orderId, @AuthenticationPrincipal LoginMember loginMember, Model model) {
		model.addAttribute("donation", donationService.findOwnOrder(orderId, loginMember.getId()));
		return "donation-result";
	}

	private static String successRedirect(String paymentKey, Donation donation) {
		return "redirect:" + UriComponentsBuilder.fromPath("/donations/success")
			.queryParam("paymentKey", paymentKey)
			.queryParam("orderId", donation.getOrderId())
			.queryParam("amount", donation.getAmount())
			.encode().toUriString();
	}

	private static String failRedirect(String code, String message, String orderId) {
		return "redirect:" + UriComponentsBuilder.fromPath("/donations/fail")
			.queryParam("code", code)
			.queryParam("message", message)
			.queryParam("orderId", orderId)
			.encode().toUriString();
	}

	/**
	 * 결제창의 주문 이름. 토스 결제창 제한(100자)에 맞춰 자른다.
	 */
	private static String orderName(Donation donation) {
		String name = "치지직 후원 - " + donation.getBroadcast().getTitle();
		return name.length() > 100 ? name.substring(0, 100) : name;
	}

	private static String requireOrderId(String orderId) {
		if (orderId == null || orderId.isBlank()) {
			throw new NotFoundException("주문번호 없음");
		}
		return orderId;
	}

	private static long parseBroadcastId(String id) {
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
