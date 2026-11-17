package com.programmers.chzzk.member;

/**
 * 회원가입 입력을 받아들일 수 없을 때. 메시지는 화면에 그대로 보여 줄 수 있는 문구다.
 */
public class SignUpException extends RuntimeException {

	public SignUpException(String message) {
		super(message, null, false, false);
	}

}
