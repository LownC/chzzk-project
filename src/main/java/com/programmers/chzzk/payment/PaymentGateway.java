package com.programmers.chzzk.payment;

/**
 * 결제 서비스(PG)와의 연결. 브라우저가 결제창에서 인증을 마치면 서버가 {@link #confirm}으로 승인을 요청하고,
 * 승인 통지(웹훅)를 받으면 {@link #find}로 결제 상태를 다시 조회해 확인한다.
 * 구현은 토스페이먼츠 테스트 모드({@link TossPaymentsGateway})와 외부 호출 없는 가짜 게이트웨이({@link FakePaymentGateway})다.
 */
public interface PaymentGateway {

	/**
	 * 화면이 어떤 결제창을 띄울지 정하는 이름("toss" 또는 "fake").
	 */
	String name();

	/**
	 * 결제 승인을 요청한다. 거절·오류면 {@link PaymentGatewayException}이다.
	 */
	PaymentApproval confirm(String paymentKey, String orderId, long amount);

	/**
	 * 결제 상태를 조회한다. 없는 결제면 {@link PaymentGatewayException}이다.
	 */
	PaymentApproval find(String paymentKey);

}
