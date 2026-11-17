package com.programmers.chzzk.payment;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 가짜 게이트웨이가 발급한 결제 기록. 인스턴스가 하나면 메모리({@link InMemory}), 여러 인스턴스(⑤ cluster)면 Redis
 * ({@link RedisFakePaymentLedger})에 둬서, 결제창 인증을 받은 인스턴스와 승인 요청을 받은 인스턴스가 달라도 같은 기록을 본다.
 * 승인은 확인과 상태 변경을 한 번에(원자적으로) 한다.
 */
public interface FakePaymentLedger {

	void save(String paymentKey, FakePayment payment);

	Optional<FakePayment> find(String paymentKey);

	/**
	 * 주문번호·금액이 인증 때와 같고 아직 승인 전이면 DONE으로 바꾼다. 거절 지정이 있으면 ABORTED로 바꾼다.
	 */
	ConfirmResult confirm(String paymentKey, String orderId, long amount);

	record FakePayment(String orderId, long amount, String status, boolean declineOnConfirm) {
	}

	enum ConfirmResult {
		DONE,
		NOT_FOUND,
		MISMATCH,
		ALREADY_DONE,
		DECLINED
	}

	/**
	 * 서버 메모리 기록(기본·테스트).
	 */
	class InMemory implements FakePaymentLedger {

		private final Map<String, FakePayment> payments = new ConcurrentHashMap<>();

		@Override
		public void save(String paymentKey, FakePayment payment) {
			payments.put(paymentKey, payment);
		}

		@Override
		public Optional<FakePayment> find(String paymentKey) {
			return Optional.ofNullable(payments.get(paymentKey));
		}

		@Override
		public synchronized ConfirmResult confirm(String paymentKey, String orderId, long amount) {
			FakePayment payment = payments.get(paymentKey);
			if (payment == null) {
				return ConfirmResult.NOT_FOUND;
			}
			if (!payment.orderId().equals(orderId) || payment.amount() != amount) {
				return ConfirmResult.MISMATCH;
			}
			if (PaymentApproval.DONE.equals(payment.status())) {
				return ConfirmResult.ALREADY_DONE;
			}
			if (payment.declineOnConfirm()) {
				payments.put(paymentKey, new FakePayment(orderId, amount, "ABORTED", true));
				return ConfirmResult.DECLINED;
			}
			payments.put(paymentKey, new FakePayment(orderId, amount, PaymentApproval.DONE, false));
			return ConfirmResult.DONE;
		}

	}

}
