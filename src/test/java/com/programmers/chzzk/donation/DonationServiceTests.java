package com.programmers.chzzk.donation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import com.programmers.chzzk.TestMembers;
import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.broadcast.BroadcastRepository;
import com.programmers.chzzk.comment.CommentRepository;
import com.programmers.chzzk.global.exception.NotFoundException;
import com.programmers.chzzk.member.Member;
import com.programmers.chzzk.member.MemberRepository;
import com.programmers.chzzk.payment.FakePaymentGateway;
import com.programmers.chzzk.payment.PaymentGateway;

/**
 * 후원 확정 규칙. 결제 서비스는 외부 호출이 없는 가짜 게이트웨이(기본값)를 쓴다.
 * 금액·주문 소유자·수신 대상 검증, 중복 승인 요청, 동시 요청, 반복 승인 통지에서 후원이 한 번만 확정되는지 확인한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class DonationServiceTests {

	@Autowired
	private DonationService donationService;

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

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private FakePaymentGateway fakeGateway;
	private Member owner;
	private Member donor;
	private Member other;
	private long broadcastId;

	@BeforeEach
	void setUp() {
		cleanUp();
		fakeGateway = (FakePaymentGateway)paymentGateway;
		owner = TestMembers.save(memberRepository, "donation_owner");
		donor = TestMembers.save(memberRepository, "donation_donor");
		other = TestMembers.save(memberRepository, "donation_other");
		broadcastId = broadcastRepository.save(
			new Broadcast("후원 방송", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4", owner)).getId();
	}

	@AfterEach
	void cleanUp() {
		donationRepository.deleteAll();
		commentRepository.deleteAll();
		broadcastRepository.deleteAll();
	}

	@Test
	void prepareCreatesReadyOrderForBroadcastOwner() {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "5000", "  응원합니다  ");

		Donation order = donationRepository.findByOrderId(orderId).orElseThrow();
		assertThat(order.getStatus()).isEqualTo(DonationStatus.READY);
		assertThat(order.getAmount()).isEqualTo(5000);
		assertThat(order.getMessage()).isEqualTo("응원합니다");
		assertThat(order.getRecipient().getId()).isEqualTo(owner.getId());
		assertThat(order.isOrderedBy(donor.getId())).isTrue();
		assertThat(donationService.totalAmount(broadcastId)).isZero();
	}

	@Test
	void prepareRejectsInvalidAmountAndMessage() {
		for (String amount : new String[] {null, "", "999", "0", "-1000", "1000001", "1.5", "abc", "1000000000"}) {
			assertThatThrownBy(() -> donationService.prepare(broadcastId, donor.getId(), amount, null))
				.as("금액 %s", amount)
				.isInstanceOf(DonationException.class)
				.hasMessage(DonationService.INVALID_AMOUNT);
		}
		assertThat(donationService.prepare(broadcastId, donor.getId(), "1000", null)).isNotBlank();
		assertThat(donationService.prepare(broadcastId, donor.getId(), "1000000", null)).isNotBlank();
		assertThatThrownBy(() -> donationService.prepare(broadcastId, donor.getId(), "1000", "가".repeat(101)))
			.hasMessage(DonationService.MESSAGE_TOO_LONG);
	}

	@Test
	void prepareRejectsSelfDonationOwnerlessAndMissingBroadcast() {
		long ownerless = broadcastRepository.save(new Broadcast("옛 방송", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c14.mp4")).getId();

		assertThatThrownBy(() -> donationService.prepare(broadcastId, owner.getId(), "1000", null))
			.hasMessage(DonationService.SELF_DONATION);
		assertThatThrownBy(() -> donationService.prepare(ownerless, donor.getId(), "1000", null))
			.hasMessage(DonationService.NO_RECIPIENT);
		assertThatThrownBy(() -> donationService.prepare(999_999L, donor.getId(), "1000", null))
			.isInstanceOf(NotFoundException.class);
		assertThat(donationRepository.count()).isZero();
	}

	@Test
	void approvedPaymentIsConfirmedOnceAndCountedInTotal() {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "3000", "첫 후원");
		String paymentKey = fakeGateway.authorize(orderId, 3000, false);
		int callsBefore = fakeGateway.confirmCallCount();

		assertThat(donationService.confirm(orderId, paymentKey, "3000", donor.getId())).isEqualTo(DonationStatus.DONE);

		assertThat(fakeGateway.confirmCallCount()).isEqualTo(callsBefore + 1);
		Donation done = donationRepository.findByOrderId(orderId).orElseThrow();
		assertThat(done.getPaymentKey()).isEqualTo(paymentKey);
		assertThat(done.getApprovedAt()).isNotNull();
		assertThat(donationService.totalAmount(broadcastId)).isEqualTo(3000);
		assertThat(donationService.findDonations(broadcastId)).extracting(Donation::getOrderId).containsExactly(orderId);
	}

	/**
	 * 사례 A: 성공 주소가 반복 호출돼도(새로고침·재전송) 결제 서비스 승인은 한 번, 후원도 한 건이다.
	 */
	@Test
	void repeatedSuccessRequestDoesNotConfirmTwice() {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "3000", null);
		String paymentKey = fakeGateway.authorize(orderId, 3000, false);
		donationService.confirm(orderId, paymentKey, "3000", donor.getId());
		int callsAfterFirst = fakeGateway.confirmCallCount();

		for (int i = 0; i < 3; i++) {
			assertThat(donationService.confirm(orderId, paymentKey, "3000", donor.getId())).isEqualTo(DonationStatus.DONE);
		}

		assertThat(fakeGateway.confirmCallCount()).isEqualTo(callsAfterFirst);
		assertThat(donationService.totalAmount(broadcastId)).isEqualTo(3000);
		assertThat(donationService.findDonations(broadcastId)).hasSize(1);
	}

	/**
	 * 사례 B: 같은 주문의 승인 요청이 동시에 와도 결제 서비스 승인 호출과 후원 확정은 한 번뿐이다.
	 */
	@Test
	void concurrentConfirmRequestsConfirmExactlyOnce() throws Exception {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "7000", null);
		String paymentKey = fakeGateway.authorize(orderId, 7000, false);
		int callsBefore = fakeGateway.confirmCallCount();
		int threads = 8;
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(threads);
		try {
			List<Future<DonationStatus>> results = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				results.add(executor.submit(() -> {
					go.await();
					return donationService.confirm(orderId, paymentKey, "7000", donor.getId());
				}));
			}
			go.countDown();
			for (Future<DonationStatus> result : results) {
				assertThat(result.get(10, TimeUnit.SECONDS)).isIn(DonationStatus.IN_PROGRESS, DonationStatus.DONE);
			}
		} finally {
			executor.shutdownNow();
		}

		assertThat(fakeGateway.confirmCallCount()).isEqualTo(callsBefore + 1);
		assertThat(donationRepository.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(DonationStatus.DONE);
		assertThat(donationService.totalAmount(broadcastId)).isEqualTo(7000);
	}

	@Test
	void tamperedAmountIsRejectedBeforeCallingPaymentService() {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "10000", null);
		String paymentKey = fakeGateway.authorize(orderId, 10000, false);
		int callsBefore = fakeGateway.confirmCallCount();

		assertThat(donationService.confirm(orderId, paymentKey, "100", donor.getId())).isEqualTo(DonationStatus.FAILED);

		assertThat(fakeGateway.confirmCallCount()).isEqualTo(callsBefore);
		assertThat(donationRepository.findByOrderId(orderId).orElseThrow().getFailureCode())
			.isEqualTo(DonationService.AMOUNT_MISMATCH);
		assertThat(donationService.totalAmount(broadcastId)).isZero();
	}

	@Test
	void anotherMemberCannotConfirmOrSeeTheOrder() {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "3000", null);
		String paymentKey = fakeGateway.authorize(orderId, 3000, false);

		assertThatThrownBy(() -> donationService.confirm(orderId, paymentKey, "3000", other.getId()))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> donationService.findOwnOrder(orderId, other.getId()))
			.isInstanceOf(AccessDeniedException.class);
		assertThatThrownBy(() -> donationService.confirm("donation-unknown", paymentKey, "3000", donor.getId()))
			.isInstanceOf(NotFoundException.class);
		assertThat(donationRepository.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(DonationStatus.READY);
	}

	@Test
	void declinedApprovalFailsAndIsNotCounted() {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "3000", null);
		String paymentKey = fakeGateway.authorize(orderId, 3000, true);

		assertThat(donationService.confirm(orderId, paymentKey, "3000", donor.getId())).isEqualTo(DonationStatus.FAILED);

		assertThat(donationRepository.findByOrderId(orderId).orElseThrow().getFailureCode())
			.isEqualTo(FakePaymentGateway.REJECT_CARD_COMPANY);
		assertThat(donationService.totalAmount(broadcastId)).isZero();
	}

	@Test
	void failAndCancelFromPaymentWindowEndOnlyUnconfirmedOrders() {
		String canceled = donationService.prepare(broadcastId, donor.getId(), "1000", null);
		String failed = donationService.prepare(broadcastId, donor.getId(), "1000", null);
		String done = donationService.prepare(broadcastId, donor.getId(), "1000", null);
		donationService.confirm(done, fakeGateway.authorize(done, 1000, false), "1000", donor.getId());

		assertThat(donationService.fail(canceled, "PAY_PROCESS_CANCELED", donor.getId())).isEqualTo(DonationStatus.CANCELED);
		assertThat(donationService.fail(failed, "REJECT_CARD_COMPANY", donor.getId())).isEqualTo(DonationStatus.FAILED);
		assertThat(donationService.fail(done, "PAY_PROCESS_CANCELED", donor.getId())).isEqualTo(DonationStatus.DONE);
		assertThat(donationService.fail(failed, "<script>", donor.getId())).isEqualTo(DonationStatus.FAILED);
		assertThat(donationService.totalAmount(broadcastId)).isEqualTo(1000);
	}

	@Test
	void paymentKeyAlreadyUsedByAnotherOrderIsRejected() {
		String first = donationService.prepare(broadcastId, donor.getId(), "2000", null);
		String paymentKey = fakeGateway.authorize(first, 2000, false);
		donationService.confirm(first, paymentKey, "2000", donor.getId());
		String second = donationService.prepare(broadcastId, donor.getId(), "2000", null);

		assertThat(donationService.confirm(second, paymentKey, "2000", donor.getId())).isEqualTo(DonationStatus.FAILED);

		assertThat(donationRepository.findByOrderId(second).orElseThrow().getFailureCode())
			.isEqualTo(DonationService.DUPLICATE_PAYMENT_KEY);
		assertThat(donationService.totalAmount(broadcastId)).isEqualTo(2000);
	}

	@Test
	void recipientDifferentFromBroadcastOwnerIsRejected() {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "2000", null);
		jdbcTemplate.update("update donation set recipient_id = ? where order_id = ?", other.getId(), orderId);
		String paymentKey = fakeGateway.authorize(orderId, 2000, false);

		assertThat(donationService.confirm(orderId, paymentKey, "2000", donor.getId())).isEqualTo(DonationStatus.FAILED);

		assertThat(donationRepository.findByOrderId(orderId).orElseThrow().getFailureCode())
			.isEqualTo(DonationService.RECIPIENT_MISMATCH);
	}

	/**
	 * 같은 승인 통지가 여러 번 와도 후원은 한 번만 확정된다.
	 */
	@Test
	void repeatedApprovalNoticeDoesNotDoubleCount() {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "5000", null);
		String paymentKey = fakeGateway.authorize(orderId, 5000, false);
		donationService.confirm(orderId, paymentKey, "5000", donor.getId());

		for (int i = 0; i < 3; i++) {
			assertThat(donationService.handleApprovalNotice(paymentKey, orderId))
				.isEqualTo(DonationService.NoticeResult.ALREADY_DONE);
		}

		assertThat(donationService.totalAmount(broadcastId)).isEqualTo(5000);
		assertThat(donationService.findDonations(broadcastId)).hasSize(1);
	}

	/**
	 * 승인 요청 뒤 서버가 확정을 기록하지 못한 주문(IN_PROGRESS)은 승인 통지로 확정된다. 이후 통지는 무시된다.
	 */
	@Test
	void approvalNoticeCompletesOrderLeftInProgress() {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "4000", null);
		String paymentKey = fakeGateway.authorize(orderId, 4000, false);
		donationRepository.claim(orderId, paymentKey);
		fakeGateway.confirm(paymentKey, orderId, 4000);

		assertThat(donationService.handleApprovalNotice(paymentKey, orderId))
			.isEqualTo(DonationService.NoticeResult.CONFIRMED);
		assertThat(donationService.handleApprovalNotice(paymentKey, orderId))
			.isEqualTo(DonationService.NoticeResult.ALREADY_DONE);
		assertThat(donationService.totalAmount(broadcastId)).isEqualTo(4000);
	}

	/**
	 * 위조 통지: 결제 서비스에 없는 결제 키이거나, 승인되지 않은 결제이거나, 다른 주문의 결제면 확정하지 않는다.
	 */
	@Test
	void forgedApprovalNoticeIsIgnored() {
		String orderId = donationService.prepare(broadcastId, donor.getId(), "4000", null);
		String unapprovedKey = fakeGateway.authorize(orderId, 4000, false);
		String otherOrder = donationService.prepare(broadcastId, donor.getId(), "4000", null);
		String otherOrderKey = fakeGateway.authorize(otherOrder, 4000, false);
		fakeGateway.confirm(otherOrderKey, otherOrder, 4000);

		assertThat(donationService.handleApprovalNotice("fake_unknown", orderId))
			.isEqualTo(DonationService.NoticeResult.IGNORED);
		assertThat(donationService.handleApprovalNotice(unapprovedKey, orderId))
			.isEqualTo(DonationService.NoticeResult.IGNORED);
		assertThat(donationService.handleApprovalNotice(otherOrderKey, orderId))
			.isEqualTo(DonationService.NoticeResult.IGNORED);
		assertThat(donationService.handleApprovalNotice(unapprovedKey, "donation-unknown"))
			.isEqualTo(DonationService.NoticeResult.IGNORED);

		assertThat(donationRepository.findByOrderId(orderId).orElseThrow().getStatus()).isEqualTo(DonationStatus.READY);
		assertThat(donationService.totalAmount(broadcastId)).isZero();
	}

}
