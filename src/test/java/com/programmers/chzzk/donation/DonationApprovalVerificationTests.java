package com.programmers.chzzk.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.programmers.chzzk.TestMembers;
import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.broadcast.BroadcastRepository;
import com.programmers.chzzk.member.Member;
import com.programmers.chzzk.member.MemberRepository;
import com.programmers.chzzk.payment.PaymentApproval;
import com.programmers.chzzk.payment.PaymentGateway;
import com.programmers.chzzk.payment.PaymentGatewayException;

/**
 * 결제 서비스가 승인 응답을 줬더라도 그 내용(상태·금액·주문번호)이 주문과 다르면 확정하지 않는다.
 * 결제 서비스 응답을 마음대로 만들기 위해 게이트웨이를 목으로 바꾼다.
 */
@SpringBootTest
@ActiveProfiles("test")
class DonationApprovalVerificationTests {

	@MockitoBean
	private PaymentGateway paymentGateway;

	@Autowired
	private DonationService donationService;

	@Autowired
	private DonationRepository donationRepository;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@Autowired
	private MemberRepository memberRepository;

	private Member donor;
	private long broadcastId;

	@BeforeEach
	void setUp() {
		cleanUp();
		Member owner = TestMembers.save(memberRepository, "verify_owner");
		donor = TestMembers.save(memberRepository, "verify_donor");
		broadcastId = broadcastRepository.save(
			new Broadcast("검증 방송", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4", owner)).getId();
	}

	@AfterEach
	void cleanUp() {
		donationRepository.deleteAll();
		broadcastRepository.deleteAll();
	}

	@Test
	void approvalWithDifferentAmountIsNotConfirmed() {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "5000", null);
		given(paymentGateway.confirm(anyString(), anyString(), anyLong()))
			.willReturn(new PaymentApproval("pk", orderId, 100, PaymentApproval.DONE));

		assertFailedWith(orderId, "pk", DonationService.APPROVAL_MISMATCH);
	}

	@Test
	void approvalForAnotherOrderIsNotConfirmed() {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "5000", null);
		given(paymentGateway.confirm(anyString(), anyString(), anyLong()))
			.willReturn(new PaymentApproval("pk", "donation-other", 5000, PaymentApproval.DONE));

		assertFailedWith(orderId, "pk", DonationService.APPROVAL_MISMATCH);
	}

	@Test
	void approvalNotDoneIsNotConfirmed() {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "5000", null);
		given(paymentGateway.confirm(anyString(), anyString(), anyLong()))
			.willReturn(new PaymentApproval("pk", orderId, 5000, "WAITING_FOR_DEPOSIT"));

		assertFailedWith(orderId, "pk", DonationService.APPROVAL_MISMATCH);
	}

	@Test
	void networkFailureDuringApprovalIsRecorded() {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "5000", null);
		given(paymentGateway.confirm(anyString(), anyString(), anyLong()))
			.willThrow(new PaymentGatewayException(PaymentGatewayException.NETWORK_ERROR, "연결 실패"));

		assertFailedWith(orderId, "pk", PaymentGatewayException.NETWORK_ERROR);
	}

	private void assertFailedWith(String orderId, String paymentKey, String failureCode) {
		assertThat(donationService.confirm(orderId, paymentKey, "5000", donor.getId())).isEqualTo(DonationStatus.FAILED);
		Donation order = donationRepository.findByOrderId(orderId).orElseThrow();
		assertThat(order.getFailureCode()).isEqualTo(failureCode);
		assertThat(donationService.totalAmount(broadcastId)).isZero();
	}

}
