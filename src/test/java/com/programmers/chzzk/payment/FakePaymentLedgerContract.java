package com.programmers.chzzk.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.programmers.chzzk.payment.FakePaymentLedger.ConfirmResult;
import com.programmers.chzzk.payment.FakePaymentLedger.FakePayment;

/**
 * (⑤) 가짜 결제 장부의 승인 규칙. 결제창을 연 인스턴스와 승인을 확인하는 인스턴스가 달라도 같은 결과가 나와야 한다.
 */
abstract class FakePaymentLedgerContract {

	private final String paymentKey = "fake_" + UUID.randomUUID();

	protected abstract FakePaymentLedger ledger();

	@Test
	void confirmMatchingPaymentOnlyOnce() {
		ledger().save(paymentKey, new FakePayment("order-1", 5000, "READY", false));

		assertThat(ledger().confirm(paymentKey, "order-1", 5000)).isEqualTo(ConfirmResult.DONE);
		assertThat(ledger().confirm(paymentKey, "order-1", 5000)).isEqualTo(ConfirmResult.ALREADY_DONE);
		assertThat(ledger().find(paymentKey)).hasValueSatisfying(payment -> {
			assertThat(payment.status()).isEqualTo("DONE");
			assertThat(payment.amount()).isEqualTo(5000);
		});
	}

	@Test
	void confirmWithDifferentOrderOrAmountIsMismatch() {
		ledger().save(paymentKey, new FakePayment("order-1", 5000, "READY", false));

		assertThat(ledger().confirm(paymentKey, "order-1", 1000)).isEqualTo(ConfirmResult.MISMATCH);
		assertThat(ledger().confirm(paymentKey, "order-2", 5000)).isEqualTo(ConfirmResult.MISMATCH);
		assertThat(ledger().find(paymentKey)).hasValueSatisfying(payment -> assertThat(payment.status()).isEqualTo("READY"));
	}

	@Test
	void confirmUnknownPaymentKeyIsNotFound() {
		assertThat(ledger().confirm(paymentKey, "order-1", 5000)).isEqualTo(ConfirmResult.NOT_FOUND);
		assertThat(ledger().find(paymentKey)).isEmpty();
	}

	@Test
	void declinedPaymentIsNeverDone() {
		ledger().save(paymentKey, new FakePayment("order-1", 5000, "READY", true));

		assertThat(ledger().confirm(paymentKey, "order-1", 5000)).isEqualTo(ConfirmResult.DECLINED);
		assertThat(ledger().confirm(paymentKey, "order-1", 5000)).isEqualTo(ConfirmResult.DECLINED);
		assertThat(ledger().find(paymentKey)).hasValueSatisfying(payment -> assertThat(payment.status()).isEqualTo("ABORTED"));
	}

	@Test
	void concurrentConfirmsApproveExactlyOnce() throws Exception {
		ledger().save(paymentKey, new FakePayment("order-1", 5000, "READY", false));
		int callers = 10;
		CountDownLatch go = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(callers);
		try {
			List<Future<ConfirmResult>> results = new ArrayList<>();
			for (int i = 0; i < callers; i++) {
				results.add(executor.submit(() -> {
					go.await();
					return ledger().confirm(paymentKey, "order-1", 5000);
				}));
			}
			go.countDown();

			int done = 0;
			for (Future<ConfirmResult> result : results) {
				if (result.get(10, TimeUnit.SECONDS) == ConfirmResult.DONE) {
					done++;
				}
			}
			assertThat(done).isEqualTo(1);
		} finally {
			executor.shutdownNow();
		}
	}

}
