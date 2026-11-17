package com.programmers.chzzk.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.programmers.chzzk.TestMembers;
import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.broadcast.BroadcastHasDonationsException;
import com.programmers.chzzk.broadcast.BroadcastRepository;
import com.programmers.chzzk.comment.CommentRepository;
import com.programmers.chzzk.member.Member;
import com.programmers.chzzk.member.MemberRepository;
import com.programmers.chzzk.payment.FakePaymentGateway;
import com.programmers.chzzk.payment.PaymentGateway;

/**
 * 후원 화면 흐름을 HTTP로 확인한다: 주문 → 가짜 결제창 → 성공·실패 주소 → 결과, 방송별 후원 내역·합계, 승인 통지.
 * 로그인·CSRF·주문자 검사와 위조된 성공 주소(금액 변경)도 확인한다. 외부 결제 서비스는 호출하지 않는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DonationWebTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private DonationRepository donationRepository;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@Autowired
	private CommentRepository commentRepository;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PaymentGateway paymentGateway;

	private Member owner;
	private Member donor;
	private Member other;
	private long broadcastId;

	@BeforeEach
	void setUp() {
		cleanUp();
		owner = TestMembers.save(memberRepository, "web_owner");
		donor = TestMembers.save(memberRepository, "web_donor");
		other = TestMembers.save(memberRepository, "web_other");
		broadcastId = broadcastRepository.save(
			new Broadcast("후원 받는 방송", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4", owner)).getId();
	}

	@AfterEach
	void cleanUp() {
		donationRepository.deleteAll();
		commentRepository.deleteAll();
		broadcastRepository.deleteAll();
	}

	@Test
	void anonymousUserCannotStartDonationButSeesHistory() throws Exception {
		mockMvc.perform(post("/broadcasts/" + broadcastId + "/donations").param("amount", "1000").with(csrf()))
			.andExpect(redirectedUrl("/login"));
		mockMvc.perform(get("/donations/donation-anything/checkout")).andExpect(redirectedUrl("/login"));
		mockMvc.perform(get("/donations/success").param("orderId", "x")).andExpect(redirectedUrl("/login"));

		mockMvc.perform(get("/broadcasts/" + broadcastId))
			.andExpect(status().isOk())
			.andExpect(xpath("//section[@id='donations']//strong[@class='donation-total']").string("0원"))
			.andExpect(xpath("//form[@class='donation-form']").doesNotExist())
			.andExpect(xpath("//p[@class='donation-login']/a[@href='/login']").exists());
		assertThat(donationRepository.count()).isZero();
	}

	@Test
	void donationWithoutCsrfTokenIsForbidden() throws Exception {
		mockMvc.perform(post("/broadcasts/" + broadcastId + "/donations").param("amount", "1000").with(as(donor)))
			.andExpect(status().isForbidden());

		assertThat(donationRepository.count()).isZero();
	}

	@Test
	void fullTestPaymentFlowConfirmsDonationAndShowsTotal() throws Exception {
		String orderId = order(donor, "5000", "화이팅");

		mockMvc.perform(get("/donations/" + orderId + "/checkout").with(as(donor)))
			.andExpect(status().isOk())
			.andExpect(xpath("//dd[@class='checkout-amount']").string("5,000원"))
			.andExpect(xpath("//dd[@class='checkout-recipient']").string("web_owner"))
			.andExpect(xpath("//main[@id='donation-checkout']/@data-gateway").string("fake"))
			.andExpect(xpath("//form[@class='fake-pay']//input[@name='_csrf']").exists())
			.andExpect(xpath("//script[contains(@src, 'tosspayments')]").doesNotExist());

		String successUrl = mockMvc.perform(post("/donations/" + orderId + "/fake-pay").param("result", "approve")
				.with(as(donor)).with(csrf()))
			.andExpect(status().is3xxRedirection())
			.andReturn().getResponse().getRedirectedUrl();
		assertThat(successUrl).startsWith("/donations/success?paymentKey=fake_").contains("orderId=" + orderId)
			.contains("amount=5000");
		assertThat(donationRepository.findByOrderId(orderId).orElseThrow().getStatus())
			.as("결제창 성공만으로는 확정되지 않는다").isEqualTo(DonationStatus.READY);

		mockMvc.perform(get(successUrl).with(as(donor))).andExpect(redirectedUrl("/donations/" + orderId));
		mockMvc.perform(get("/donations/" + orderId).with(as(donor)))
			.andExpect(xpath("//p[@class='donation-status']/span").string("후원이 완료되었습니다. 감사합니다!"));

		mockMvc.perform(get("/broadcasts/" + broadcastId))
			.andExpect(xpath("//strong[@class='donation-total']").string("5,000원"))
			.andExpect(xpath("//span[@class='donation-count']").string("1"))
			.andExpect(xpath("//ul[@class='donation-list']/li/strong[@class='donation-donor']").string("web_donor"))
			.andExpect(xpath("//ul[@class='donation-list']/li/p[@class='donation-message']").string("화이팅"));
	}

	@Test
	void reloadingSuccessUrlDoesNotDoubleCount() throws Exception {
		String orderId = order(donor, "2000", null);
		String successUrl = approve(orderId);

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(get(successUrl).with(as(donor))).andExpect(redirectedUrl("/donations/" + orderId));
		}

		mockMvc.perform(get("/broadcasts/" + broadcastId))
			.andExpect(xpath("//strong[@class='donation-total']").string("2,000원"))
			.andExpect(xpath("//span[@class='donation-count']").string("1"));
	}

	@Test
	void tamperedSuccessUrlAmountIsNotConfirmed() throws Exception {
		String orderId = order(donor, "50000", null);
		String successUrl = approve(orderId).replace("amount=50000", "amount=1000");

		mockMvc.perform(get(successUrl).with(as(donor)));

		assertThat(donationRepository.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(DonationStatus.FAILED);
		mockMvc.perform(get("/donations/" + orderId).with(as(donor)))
			.andExpect(xpath("//p[@class='donation-failure']").string("사유 코드: " + DonationService.AMOUNT_MISMATCH));
		mockMvc.perform(get("/broadcasts/" + broadcastId)).andExpect(xpath("//strong[@class='donation-total']").string("0원"));
	}

	/**
	 * README 직접 확인 4번: 결제하지 않고 성공 주소만 직접 열어도 결제 서비스에 결제가 없으므로 확정되지 않는다.
	 */
	@Test
	void successUrlWithoutPaymentIsNotConfirmed() throws Exception {
		String orderId = order(donor, "50000", null);

		mockMvc.perform(get("/donations/success").param("paymentKey", "fake_test").param("orderId", orderId)
				.param("amount", "50000").with(as(donor)))
			.andExpect(redirectedUrl("/donations/" + orderId));

		Donation order = donationRepository.findByOrderId(orderId).orElseThrow();
		assertThat(order.getStatus()).isEqualTo(DonationStatus.FAILED);
		assertThat(order.getFailureCode()).isEqualTo("NOT_FOUND_PAYMENT");
		mockMvc.perform(get("/broadcasts/" + broadcastId)).andExpect(xpath("//strong[@class='donation-total']").string("0원"));
	}

	@Test
	void otherMemberCannotUseSomeoneElsesOrder() throws Exception {
		String orderId = order(donor, "3000", null);
		String successUrl = approve(orderId);

		mockMvc.perform(get("/donations/" + orderId + "/checkout").with(as(other))).andExpect(status().isForbidden());
		mockMvc.perform(get(successUrl).with(as(other))).andExpect(status().isForbidden());
		mockMvc.perform(get("/donations/" + orderId).with(as(other))).andExpect(status().isForbidden());
		mockMvc.perform(post("/donations/" + orderId + "/fake-pay").param("result", "approve").with(as(other)).with(csrf()))
			.andExpect(status().isForbidden());

		assertThat(donationRepository.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(DonationStatus.READY);
	}

	@Test
	void cancelFailAndDeclineAreRecordedWithoutDonation() throws Exception {
		String canceled = order(donor, "1000", null);
		String failed = order(donor, "1000", null);
		String declined = order(donor, "1000", null);

		followFakePay(canceled, "cancel");
		followFakePay(failed, "fail");
		followFakePay(declined, "decline");

		assertThat(donationRepository.findByOrderId(canceled).orElseThrow().getStatus()).isEqualTo(DonationStatus.CANCELED);
		assertThat(donationRepository.findByOrderId(failed).orElseThrow().getStatus()).isEqualTo(DonationStatus.FAILED);
		assertThat(donationRepository.findByOrderId(declined).orElseThrow().getStatus()).isEqualTo(DonationStatus.FAILED);
		mockMvc.perform(get("/donations/" + canceled).with(as(donor)))
			.andExpect(xpath("//p[@class='donation-status']/span").string("결제를 취소했습니다. 후원되지 않았습니다."));
		mockMvc.perform(get("/broadcasts/" + broadcastId)).andExpect(xpath("//strong[@class='donation-total']").string("0원"));
	}

	@Test
	void invalidDonationInputShowsMessage() throws Exception {
		mockMvc.perform(post("/broadcasts/" + broadcastId + "/donations").param("amount", "500").with(as(donor)).with(csrf()))
			.andExpect(redirectedUrl("/broadcasts/" + broadcastId + "#donations"))
			.andExpect(flash().attribute(DonationController.DONATION_ERROR, DonationService.INVALID_AMOUNT));
		mockMvc.perform(post("/broadcasts/" + broadcastId + "/donations").param("amount", "1000").with(as(owner)).with(csrf()))
			.andExpect(flash().attribute(DonationController.DONATION_ERROR, DonationService.SELF_DONATION));
		mockMvc.perform(post("/broadcasts/999999/donations").param("amount", "1000").with(as(donor)).with(csrf()))
			.andExpect(status().isNotFound());

		assertThat(donationRepository.count()).isZero();
	}

	@Test
	void donationFormIsShownOnlyToOtherLoggedInMembers() throws Exception {
		mockMvc.perform(get("/broadcasts/" + broadcastId).with(as(donor)))
			.andExpect(xpath("//form[@class='donation-form']//input[@name='_csrf']").exists())
			.andExpect(xpath("//form[@class='donation-form']//input[@name='amount'][@min='1000'][@max='1000000']").exists());
		mockMvc.perform(get("/broadcasts/" + broadcastId).with(as(owner)))
			.andExpect(xpath("//form[@class='donation-form']").doesNotExist())
			.andExpect(xpath("//p[@class='donation-own']").exists());
	}

	@Test
	void repeatedApprovalNoticeWebhookIsAcceptedOnceWithoutLoginOrCsrf() throws Exception {
		String orderId = order(donor, "6000", null);
		String successUrl = approve(orderId);
		mockMvc.perform(get(successUrl).with(as(donor)));
		String paymentKey = donationRepository.findByOrderId(orderId).orElseThrow().getPaymentKey();
		String notice = "{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{\"paymentKey\":\"" + paymentKey
			+ "\",\"orderId\":\"" + orderId + "\",\"status\":\"DONE\"}}";

		for (int i = 0; i < 3; i++) {
			mockMvc.perform(post("/payments/toss/webhook").contentType(MediaType.APPLICATION_JSON).content(notice))
				.andExpect(status().isOk())
				.andExpect(content().json("{\"result\":\"ALREADY_DONE\"}"));
		}

		mockMvc.perform(get("/broadcasts/" + broadcastId))
			.andExpect(xpath("//strong[@class='donation-total']").string("6,000원"))
			.andExpect(xpath("//span[@class='donation-count']").string("1"));
	}

	@Test
	void malformedOrUnrelatedWebhookIsRejectedOrIgnored() throws Exception {
		mockMvc.perform(post("/payments/toss/webhook").contentType(MediaType.APPLICATION_JSON).content("not json"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post("/payments/toss/webhook").contentType(MediaType.APPLICATION_JSON)
				.content("{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{}}"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post("/payments/toss/webhook").contentType(MediaType.APPLICATION_JSON)
				.content("{\"eventType\":\"DEPOSIT_CALLBACK\",\"data\":{}}"))
			.andExpect(content().json("{\"result\":\"IGNORED\"}"));
		mockMvc.perform(post("/payments/toss/webhook").contentType(MediaType.APPLICATION_JSON)
				.content("{\"eventType\":\"PAYMENT_STATUS_CHANGED\",\"data\":{\"paymentKey\":\"forged\",\"orderId\":\"donation-x\"}}"))
			.andExpect(content().json("{\"result\":\"IGNORED\"}"));
	}

	@Test
	void broadcastWithConfirmedDonationIsNotDeleted() throws Exception {
		String orderId = order(donor, "1000", null);
		mockMvc.perform(get(approve(orderId)).with(as(donor)));

		mockMvc.perform(post("/broadcasts/" + broadcastId + "/delete").with(as(owner)).with(csrf()))
			.andExpect(redirectedUrl("/broadcasts/" + broadcastId))
			.andExpect(flash().attribute("manageError", BroadcastHasDonationsException.MESSAGE));

		assertThat(broadcastRepository.existsById(broadcastId)).isTrue();
		assertThat(donationRepository.count()).isEqualTo(1);
	}

	@Test
	void broadcastWithOnlyUnconfirmedOrdersIsDeletedWithThem() throws Exception {
		followFakePay(order(donor, "1000", null), "cancel");
		order(donor, "1000", null);

		mockMvc.perform(post("/broadcasts/" + broadcastId + "/delete").with(as(owner)).with(csrf()))
			.andExpect(redirectedUrl("/broadcasts"));

		assertThat(broadcastRepository.existsById(broadcastId)).isFalse();
		assertThat(donationRepository.count()).isZero();
	}

	private String order(Member member, String amount, String message) throws Exception {
		var request = post("/broadcasts/" + broadcastId + "/donations").param("amount", amount).with(as(member)).with(csrf());
		if (message != null) {
			request.param("message", message);
		}
		String checkoutUrl = mockMvc.perform(request).andExpect(status().is3xxRedirection())
			.andReturn().getResponse().getRedirectedUrl();
		assertThat(checkoutUrl).startsWith("/donations/donation-").endsWith("/checkout");
		return checkoutUrl.substring("/donations/".length(), checkoutUrl.length() - "/checkout".length());
	}

	/**
	 * 가짜 결제창에서 승인을 눌러 결제창 성공 주소를 얻는다(아직 확정 전).
	 */
	private String approve(String orderId) throws Exception {
		return mockMvc.perform(post("/donations/" + orderId + "/fake-pay").param("result", "approve")
				.with(as(donor)).with(csrf()))
			.andReturn().getResponse().getRedirectedUrl();
	}

	private void followFakePay(String orderId, String result) throws Exception {
		String next = mockMvc.perform(post("/donations/" + orderId + "/fake-pay").param("result", result)
				.with(as(donor)).with(csrf()))
			.andReturn().getResponse().getRedirectedUrl();
		mockMvc.perform(get(next).with(as(donor))).andExpect(redirectedUrl("/donations/" + orderId));
	}

	private static RequestPostProcessor as(Member member) {
		return user(TestMembers.loginMember(member));
	}

	@SuppressWarnings("unused")
	private FakePaymentGateway fakeGateway() {
		return (FakePaymentGateway)paymentGateway;
	}

}
