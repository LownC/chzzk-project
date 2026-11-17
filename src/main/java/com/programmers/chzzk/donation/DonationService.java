package com.programmers.chzzk.donation;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.broadcast.BroadcastRepository;
import com.programmers.chzzk.global.exception.NotFoundException;
import com.programmers.chzzk.member.MemberRepository;
import com.programmers.chzzk.payment.PaymentApproval;
import com.programmers.chzzk.payment.PaymentGateway;
import com.programmers.chzzk.payment.PaymentGatewayException;

/**
 * 도네이션(후원). 흐름은 토스페이먼츠 결제 위젯 방식과 같다.
 * <ol>
 *     <li>{@link #prepare}: 로그인 회원이 방송과 금액을 고르면 서버가 주문(READY)을 만든다. 금액과 수신 대상(방송 소유자)은 여기서 서버가 정한다.</li>
 *     <li>브라우저가 결제창을 띄우고, 인증이 끝나면 결제 서비스가 성공 주소로 {@code paymentKey, orderId, amount}를 붙여 돌려보낸다.</li>
 *     <li>{@link #confirm}: 서버가 주문 소유자·금액·수신 대상을 확인하고, 주문 하나당 한 요청만 승인을 맡아(READY→IN_PROGRESS)
 *     결제 서비스에 승인을 요청한다. 승인 결과의 상태·주문번호·금액·결제 키가 주문과 같을 때만 DONE으로 확정한다.</li>
 *     <li>{@link #handleApprovalNotice}: 결제 서비스의 승인 통지(웹훅)는 본문을 믿지 않고 결제 서비스에 다시 조회해 확인한다.
 *     이미 확정된 주문이면 아무것도 하지 않는다.</li>
 * </ol>
 * 화면의 성공 표시만으로는 확정되지 않으며, 같은 승인 통지·성공 요청이 반복되거나 동시에 와도 후원은 한 번만 적립된다.
 */
@Service
public class DonationService {

	private static final Logger log = LoggerFactory.getLogger(DonationService.class);

	public static final long MIN_AMOUNT = 1_000;
	public static final long MAX_AMOUNT = 1_000_000;
	public static final int MAX_MESSAGE_LENGTH = 100;

	static final String INVALID_AMOUNT = "후원 금액은 1,000원 이상 1,000,000원 이하의 정수로 입력해 주세요.";
	static final String MESSAGE_TOO_LONG = "후원 메시지는 최대 100자까지 쓸 수 있습니다.";
	static final String NO_RECIPIENT = "방송자가 없는 방송(3단계 이전 방송)에는 후원할 수 없습니다.";
	static final String SELF_DONATION = "자신의 방송에는 후원할 수 없습니다.";

	static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
	static final String RECIPIENT_MISMATCH = "RECIPIENT_MISMATCH";
	static final String INVALID_PAYMENT_KEY = "INVALID_PAYMENT_KEY";
	static final String DUPLICATE_PAYMENT_KEY = "DUPLICATE_PAYMENT_KEY";
	static final String APPROVAL_MISMATCH = "APPROVAL_MISMATCH";
	static final String USER_CANCELED = "PAY_PROCESS_CANCELED";

	private static final Pattern AMOUNT = Pattern.compile("\\d{1,9}");
	private static final Pattern FAILURE_CODE = Pattern.compile("[A-Z0-9_]{1,60}");
	private static final int MAX_PAYMENT_KEY_LENGTH = 200;

	private final DonationRepository donationRepository;
	private final BroadcastRepository broadcastRepository;
	private final MemberRepository memberRepository;
	private final PaymentGateway paymentGateway;
	private final Clock clock;

	public DonationService(DonationRepository donationRepository, BroadcastRepository broadcastRepository,
		MemberRepository memberRepository, PaymentGateway paymentGateway, Clock clock) {
		this.donationRepository = donationRepository;
		this.broadcastRepository = broadcastRepository;
		this.memberRepository = memberRepository;
		this.paymentGateway = paymentGateway;
		this.clock = clock;
	}

	/**
	 * 후원 주문을 만든다. 수신 대상은 방송 소유자이고, 소유자 없는 방송과 자기 방송은 후원할 수 없다.
	 *
	 * @return 결제창에 넘길 주문번호
	 */
	@Transactional
	public String prepare(long broadcastId, long donorId, String amountText, String message) {
		Broadcast broadcast = broadcastRepository.findById(broadcastId)
			.orElseThrow(() -> new NotFoundException("방송 없음: id=" + broadcastId));
		if (broadcast.getOwner() == null) {
			throw new DonationException(NO_RECIPIENT);
		}
		if (broadcast.isOwnedBy(donorId)) {
			throw new DonationException(SELF_DONATION);
		}
		long amount = parseAmount(amountText)
			.filter(value -> value >= MIN_AMOUNT && value <= MAX_AMOUNT)
			.orElseThrow(() -> new DonationException(INVALID_AMOUNT));
		String orderId = "donation-" + UUID.randomUUID();
		donationRepository.save(new Donation(orderId, broadcast, memberRepository.getReferenceById(donorId),
			broadcast.getOwner(), amount, normalizeMessage(message)));
		log.info("후원 주문 생성: orderId={}, broadcastId={}, donorId={}, amount={}", orderId, broadcastId, donorId, amount);
		return orderId;
	}

	/**
	 * 주문한 회원만 볼 수 있다. 없는 주문은 {@link NotFoundException}, 남의 주문은 {@link AccessDeniedException}이다.
	 */
	@Transactional(readOnly = true)
	public Donation findOwnOrder(String orderId, long memberId) {
		Donation donation = donationRepository.findByOrderId(orderId)
			.orElseThrow(() -> new NotFoundException("후원 주문 없음"));
		if (!donation.isOrderedBy(memberId)) {
			throw new AccessDeniedException("후원 주문자가 아님: memberId=" + memberId);
		}
		return donation;
	}

	/**
	 * 결제창 인증 성공 뒤 돌아온 요청을 처리한다. 확정 여부와 상관없이 처리 뒤의 주문 상태를 돌려준다.
	 * 이미 확정·처리 중·실패한 주문이면 아무것도 바꾸지 않는다(새로고침·중복 요청에도 한 번만 적립).
	 */
	public DonationStatus confirm(String orderId, String paymentKey, String amountText, long memberId) {
		Donation donation = findOwnOrder(orderId, memberId);
		if (donation.getStatus() != DonationStatus.READY) {
			if (donation.getStatus() == DonationStatus.DONE) {
				log.info("이미 확정된 후원의 승인 요청 반복: orderId={}", orderId);
			}
			return donation.getStatus();
		}
		Optional<Long> requestedAmount = parseAmount(amountText);
		if (requestedAmount.isEmpty() || requestedAmount.get() != donation.getAmount()) {
			log.warn("후원 금액 불일치로 승인 거절: orderId={}, expected={}, requested={}", orderId, donation.getAmount(),
				amountText);
			return finishBeforeConfirm(orderId, DonationStatus.FAILED, AMOUNT_MISMATCH);
		}
		if (paymentKey == null || paymentKey.isBlank() || paymentKey.length() > MAX_PAYMENT_KEY_LENGTH) {
			return finishBeforeConfirm(orderId, DonationStatus.FAILED, INVALID_PAYMENT_KEY);
		}
		if (!recipientStillValid(donation)) {
			log.warn("후원 수신 대상 불일치로 승인 거절: orderId={}", orderId);
			return finishBeforeConfirm(orderId, DonationStatus.FAILED, RECIPIENT_MISMATCH);
		}
		if (!claim(orderId, paymentKey)) {
			return currentStatus(orderId);
		}
		PaymentApproval approval;
		try {
			approval = paymentGateway.confirm(paymentKey, orderId, donation.getAmount());
		} catch (PaymentGatewayException e) {
			log.info("결제 승인 거절: orderId={}, code={}", orderId, e.getCode());
			donationRepository.finish(orderId, DonationStatus.FAILED, failureCode(e.getCode()),
				List.of(DonationStatus.IN_PROGRESS));
			return currentStatus(orderId);
		}
		return completeIfApproved(donation, paymentKey, approval);
	}

	/**
	 * 결제창에서 실패하거나 사용자가 취소해 실패 주소로 돌아온 요청. 아직 READY인 주문만 FAILED·CANCELED로 끝낸다.
	 */
	public DonationStatus fail(String orderId, String code, long memberId) {
		Donation donation = findOwnOrder(orderId, memberId);
		if (donation.getStatus() != DonationStatus.READY) {
			return donation.getStatus();
		}
		String failureCode = failureCode(code);
		DonationStatus status = USER_CANCELED.equals(failureCode) ? DonationStatus.CANCELED : DonationStatus.FAILED;
		return finishBeforeConfirm(orderId, status, failureCode);
	}

	/**
	 * 결제 서비스의 승인 통지(웹훅). 통지 본문은 위조될 수 있으므로 결제 키로 결제 서비스에 다시 조회해,
	 * 승인 완료(DONE)이고 주문번호·금액이 주문과 같을 때만 확정한다. 같은 통지가 여러 번 와도 한 번만 확정된다.
	 */
	public NoticeResult handleApprovalNotice(String paymentKey, String orderId) {
		Optional<Donation> found = donationRepository.findByOrderId(orderId);
		if (found.isEmpty()) {
			log.warn("알 수 없는 주문의 승인 통지 무시");
			return NoticeResult.IGNORED;
		}
		Donation donation = found.get();
		if (donation.getStatus() == DonationStatus.DONE) {
			log.info("이미 확정된 후원의 승인 통지 반복 무시: orderId={}", orderId);
			return NoticeResult.ALREADY_DONE;
		}
		if (donation.getStatus() != DonationStatus.READY && donation.getStatus() != DonationStatus.IN_PROGRESS) {
			return NoticeResult.IGNORED;
		}
		PaymentApproval approval;
		try {
			approval = paymentGateway.find(paymentKey);
		} catch (PaymentGatewayException e) {
			log.warn("승인 통지의 결제 조회 실패: orderId={}, code={}", orderId, e.getCode());
			return NoticeResult.IGNORED;
		}
		if (!matches(donation, paymentKey, approval)) {
			log.warn("승인 통지와 결제 조회 결과가 주문과 다름: orderId={}", orderId);
			return NoticeResult.IGNORED;
		}
		if (donation.getStatus() == DonationStatus.READY) {
			if (!recipientStillValid(donation) || !claim(orderId, paymentKey)) {
				return currentStatus(orderId) == DonationStatus.DONE ? NoticeResult.ALREADY_DONE : NoticeResult.IGNORED;
			}
		} else if (!paymentKey.equals(donation.getPaymentKey())) {
			return NoticeResult.IGNORED;
		}
		return completeIfApproved(donation, paymentKey, approval) == DonationStatus.DONE
			? NoticeResult.CONFIRMED : NoticeResult.IGNORED;
	}

	/**
	 * 방송의 확정된 후원(최근 승인 순).
	 */
	@Transactional(readOnly = true)
	public List<Donation> findDonations(long broadcastId) {
		return donationRepository.findByBroadcastIdAndStatusOrderByApprovedAtDescIdDesc(broadcastId, DonationStatus.DONE);
	}

	@Transactional(readOnly = true)
	public long totalAmount(long broadcastId) {
		return donationRepository.sumAmount(broadcastId, DonationStatus.DONE);
	}

	/**
	 * 승인 요청을 맡는다. 다른 요청이 먼저 맡았거나 결제 키가 다른 주문에 이미 쓰였으면 false다.
	 */
	private boolean claim(String orderId, String paymentKey) {
		try {
			return donationRepository.claim(orderId, paymentKey) == 1;
		} catch (DataIntegrityViolationException e) {
			log.warn("다른 주문에 쓰인 결제 키로 승인 요청: orderId={}", orderId);
			finishBeforeConfirm(orderId, DonationStatus.FAILED, DUPLICATE_PAYMENT_KEY);
			return false;
		}
	}

	private DonationStatus completeIfApproved(Donation donation, String paymentKey, PaymentApproval approval) {
		String orderId = donation.getOrderId();
		if (!matches(donation, paymentKey, approval)) {
			log.error("결제 승인 결과가 주문과 다름, 결제 서비스에서 확인 필요: orderId={}, status={}, totalAmount={}",
				orderId, approval.status(), approval.totalAmount());
			donationRepository.finish(orderId, DonationStatus.FAILED, APPROVAL_MISMATCH, List.of(DonationStatus.IN_PROGRESS));
			return currentStatus(orderId);
		}
		if (donationRepository.complete(orderId, paymentKey, LocalDateTime.now(clock)) == 1) {
			log.info("후원 확정: orderId={}, broadcastId={}, amount={}", orderId, donation.getBroadcast().getId(),
				donation.getAmount());
		}
		return currentStatus(orderId);
	}

	private static boolean matches(Donation donation, String paymentKey, PaymentApproval approval) {
		return approval.isDone()
			&& donation.getOrderId().equals(approval.orderId())
			&& paymentKey.equals(approval.paymentKey())
			&& donation.getAmount() == approval.totalAmount();
	}

	/**
	 * 확정 직전에도 수신 대상이 그 방송의 소유자인지 다시 본다(방송이 삭제됐거나 소유자가 다르면 확정하지 않음).
	 */
	private boolean recipientStillValid(Donation donation) {
		return broadcastRepository.findById(donation.getBroadcast().getId())
			.map(broadcast -> broadcast.isOwnedBy(donation.getRecipient().getId())
				&& !broadcast.isOwnedBy(donation.getDonor().getId()))
			.orElse(false);
	}

	private DonationStatus finishBeforeConfirm(String orderId, DonationStatus status, String failureCode) {
		donationRepository.finish(orderId, status, failureCode, List.of(DonationStatus.READY));
		return currentStatus(orderId);
	}

	private DonationStatus currentStatus(String orderId) {
		return donationRepository.findByOrderId(orderId).map(Donation::getStatus)
			.orElseThrow(() -> new NotFoundException("후원 주문 없음"));
	}

	private static Optional<Long> parseAmount(String amountText) {
		if (amountText == null || !AMOUNT.matcher(amountText.strip()).matches()) {
			return Optional.empty();
		}
		return Optional.of(Long.parseLong(amountText.strip()));
	}

	private static String normalizeMessage(String message) {
		if (message == null || message.isBlank()) {
			return null;
		}
		String stripped = message.strip();
		if (stripped.length() > MAX_MESSAGE_LENGTH) {
			throw new DonationException(MESSAGE_TOO_LONG);
		}
		return stripped;
	}

	/**
	 * 결제 서비스·결제창이 준 오류 코드를 저장 가능한 형태로 제한한다(그 밖의 값은 UNKNOWN).
	 */
	private static String failureCode(String code) {
		return code != null && FAILURE_CODE.matcher(code).matches() ? code : "UNKNOWN";
	}

	public enum NoticeResult {
		CONFIRMED,
		ALREADY_DONE,
		IGNORED
	}

}
