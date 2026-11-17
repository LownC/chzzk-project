package com.programmers.chzzk.donation;

/**
 * 후원 주문을 만들 수 없을 때(금액·메시지 오류, 소유자 없는 방송, 자기 방송 후원). 메시지는 화면에 그대로 보여 준다.
 */
public class DonationException extends RuntimeException {

	public DonationException(String message) {
		super(message, null, false, false);
	}

}
