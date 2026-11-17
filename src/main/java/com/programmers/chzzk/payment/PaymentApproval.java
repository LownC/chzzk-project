package com.programmers.chzzk.payment;

/**
 * 결제 서비스가 돌려준 결제 상태. 서버는 이 값의 주문번호·금액·상태를 저장된 후원과 비교한 뒤에만 후원을 확정한다.
 *
 * @param status 토스페이먼츠 결제 상태(예: {@code DONE}, {@code READY}, {@code CANCELED})
 */
public record PaymentApproval(String paymentKey, String orderId, long totalAmount, String status) {

	public static final String DONE = "DONE";

	public boolean isDone() {
		return DONE.equals(status);
	}

}
