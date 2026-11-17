package com.programmers.chzzk.broadcast;

/**
 * 방송 중인 라이브를 삭제하려 할 때. 소유자는 먼저 방송을 종료해야 한다.
 */
public class BroadcastStillLiveException extends RuntimeException {

	public static final String MESSAGE = "방송 중인 라이브는 삭제할 수 없습니다. 먼저 방송을 종료해 주세요.";

	public BroadcastStillLiveException() {
		super(MESSAGE, null, false, false);
	}

}
