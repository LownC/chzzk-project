package com.programmers.chzzk.donation;

/**
 * 후원 주문의 상태. READY에서 시작하고, 승인 요청을 맡은 요청 하나만 IN_PROGRESS로 바꿀 수 있다.
 * <pre>
 * READY ──(승인 요청 시작)──▶ IN_PROGRESS ──(결제 서비스 승인 + 검증 통과)──▶ DONE
 *   │                             └──(거절·검증 실패)──▶ FAILED
 *   ├──(결제창에서 취소)──▶ CANCELED
 *   └──(결제창 실패·금액 위조·수신 대상 불일치)──▶ FAILED
 * </pre>
 * DONE만 방송의 후원 내역과 합계에 들어간다.
 */
public enum DonationStatus {
	READY,
	IN_PROGRESS,
	DONE,
	FAILED,
	CANCELED
}
