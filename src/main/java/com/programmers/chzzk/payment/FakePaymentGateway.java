package com.programmers.chzzk.payment;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.programmers.chzzk.payment.FakePaymentLedger.FakePayment;

/**
 * 외부 호출 없이 토스페이먼츠 테스트 모드의 동작을 흉내 내는 게이트웨이. 자동 테스트와 로컬 검증용이며 기본값이다.
 * <ol>
 *     <li>결제창 인증 성공: {@link #authorize}가 결제 키를 발급한다(토스의 결제 인증 완료, 상태 READY).</li>
 *     <li>서버 승인: {@link #confirm}이 주문번호·금액이 인증 때와 같은지 보고 DONE으로 바꾼다.
 *     이미 승인된 결제는 {@code ALREADY_PROCESSED_PAYMENT}, 금액이 다르면 {@code INVALID_REQUEST}로 거절한다.</li>
 * </ol>
 * 결제 기록은 {@link FakePaymentLedger}에 둔다(인스턴스 하나면 메모리, ⑤ 여러 인스턴스면 Redis). 실제 돈이나 카드 정보는 다루지 않는다.
 */
public class FakePaymentGateway implements PaymentGateway {

	public static final String NAME = "fake";
	public static final String REJECT_CARD_COMPANY = "REJECT_CARD_COMPANY";

	private final FakePaymentLedger ledger;
	private final AtomicInteger confirmCalls = new AtomicInteger();

	public FakePaymentGateway() {
		this(new FakePaymentLedger.InMemory());
	}

	public FakePaymentGateway(FakePaymentLedger ledger) {
		this.ledger = ledger;
	}

	@Override
	public String name() {
		return NAME;
	}

	/**
	 * 결제창 인증이 끝난 결제를 만든다. {@code declineOnConfirm}이면 승인 단계에서 카드사 거절을 낸다.
	 */
	public String authorize(String orderId, long amount, boolean declineOnConfirm) {
		String paymentKey = "fake_" + UUID.randomUUID();
		ledger.save(paymentKey, new FakePayment(orderId, amount, "READY", declineOnConfirm));
		return paymentKey;
	}

	@Override
	public PaymentApproval confirm(String paymentKey, String orderId, long amount) {
		confirmCalls.incrementAndGet();
		return switch (ledger.confirm(paymentKey, orderId, amount)) {
			case DONE -> new PaymentApproval(paymentKey, orderId, amount, PaymentApproval.DONE);
			case NOT_FOUND -> throw new PaymentGatewayException(PaymentGatewayException.NOT_FOUND_PAYMENT,
				"결제 정보를 찾을 수 없습니다.");
			case MISMATCH -> throw new PaymentGatewayException("INVALID_REQUEST", "결제 정보가 인증 때와 다릅니다.");
			case ALREADY_DONE -> throw new PaymentGatewayException("ALREADY_PROCESSED_PAYMENT", "이미 처리된 결제입니다.");
			case DECLINED -> throw new PaymentGatewayException(REJECT_CARD_COMPANY, "카드사에서 결제를 거절했습니다.");
		};
	}

	@Override
	public PaymentApproval find(String paymentKey) {
		FakePayment payment = ledger.find(paymentKey).orElseThrow(() -> new PaymentGatewayException(
			PaymentGatewayException.NOT_FOUND_PAYMENT, "결제 정보를 찾을 수 없습니다."));
		return new PaymentApproval(paymentKey, payment.orderId(), payment.amount(), payment.status());
	}

	/**
	 * 테스트용: 이 인스턴스가 지금까지 승인 요청을 받은 횟수(중복·동시 요청에서 실제 승인 호출이 한 번뿐인지 확인).
	 */
	public int confirmCallCount() {
		return confirmCalls.get();
	}

}
