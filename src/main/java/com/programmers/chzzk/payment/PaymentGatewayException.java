package com.programmers.chzzk.payment;

/**
 * 결제 서비스가 승인을 거절했거나 응답하지 못했을 때. {@link #getCode()}는 결제 서비스의 오류 코드다
 * (예: {@code REJECT_CARD_COMPANY}, {@code ALREADY_PROCESSED_PAYMENT}). 연결 실패는 {@link #NETWORK_ERROR}다.
 */
public class PaymentGatewayException extends RuntimeException {

	public static final String NETWORK_ERROR = "NETWORK_ERROR";
	public static final String NOT_FOUND_PAYMENT = "NOT_FOUND_PAYMENT";

	private final String code;

	public PaymentGatewayException(String code, String message) {
		super(message);
		this.code = code;
	}

	public PaymentGatewayException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public String getCode() {
		return code;
	}

}
