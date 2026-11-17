package com.programmers.chzzk.payment;

/**
 * 한 서버(기본 설정)에서 쓰는 메모리 가짜 결제 장부가 승인 규칙을 지키는지 확인한다.
 */
class InMemoryFakePaymentLedgerTests extends FakePaymentLedgerContract {

	private final FakePaymentLedger ledger = new FakePaymentLedger.InMemory();

	@Override
	protected FakePaymentLedger ledger() {
		return ledger;
	}

}
