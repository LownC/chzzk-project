package com.programmers.chzzk.broadcast;

import org.springframework.http.HttpStatus;

/**
 * 방송 등록 실패 유형과 사용자에게 보여 줄 안내 문구.
 */
public enum BroadcastRegistrationError {

	TITLE_REQUIRED(HttpStatus.BAD_REQUEST, "제목을 입력해 주세요."),
	TITLE_TOO_LONG(HttpStatus.BAD_REQUEST, "제목은 최대 100자까지 입력할 수 있습니다."),
	VIDEO_FILE_REQUIRED(HttpStatus.BAD_REQUEST, "영상 파일을 선택해 주세요."),
	VIDEO_FILE_EMPTY(HttpStatus.BAD_REQUEST, "영상 파일이 비어 있습니다."),
	VIDEO_FILE_NOT_MP4(HttpStatus.BAD_REQUEST, "MP4 형식의 영상 파일만 업로드할 수 있습니다."),
	VIDEO_FILE_TOO_LARGE(HttpStatus.BAD_REQUEST, "영상 파일은 최대 500MB까지 업로드할 수 있습니다."),
	VIDEO_FILE_STORE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "영상 업로드에 실패했습니다. 잠시 후 다시 시도해 주세요."),
	REGISTRATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "방송 등록에 실패했습니다. 잠시 후 다시 시도해 주세요.");

	private final HttpStatus status;
	private final String message;

	BroadcastRegistrationError(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus status() {
		return status;
	}

	public String message() {
		return message;
	}

}
