package com.programmers.chzzk.comment;

/**
 * 댓글 내용을 받아들일 수 없을 때. 메시지는 화면에 그대로 보여 줄 수 있는 문구다.
 */
public class CommentValidationException extends RuntimeException {

	public CommentValidationException(String message) {
		super(message, null, false, false);
	}

}
