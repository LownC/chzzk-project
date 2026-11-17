package com.programmers.chzzk.broadcast;

/**
 * 확정됐거나 승인 처리 중인 후원이 있는 방송을 삭제하려 할 때. 결제 기록을 지키기 위해 삭제하지 않는다.
 */
public class BroadcastHasDonationsException extends RuntimeException {

	public static final String MESSAGE = "후원 내역이 있는 방송은 삭제할 수 없습니다.";

	public BroadcastHasDonationsException() {
		super(MESSAGE, null, false, false);
	}

}
