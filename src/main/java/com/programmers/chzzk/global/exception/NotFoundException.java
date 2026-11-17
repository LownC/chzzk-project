package com.programmers.chzzk.global.exception;

/**
 * 요청한 방송·댓글이 없을 때. 404 화면으로 응답한다.
 */
public class NotFoundException extends RuntimeException {

	public NotFoundException(String message) {
		super(message, null, false, false);
	}

}
