package com.programmers.chzzk.payment;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import com.programmers.chzzk.TestRedis;

/**
 * (⑤) 여러 인스턴스가 함께 쓰는 Redis 가짜 결제 장부가 메모리 구현과 같은 규칙을 지키는지 실제 Redis로 확인한다.
 * 외부 결제 서버는 부르지 않는다. CHZZK_TEST_REDIS_PORT를 지정했을 때만 실행한다.
 */
@EnabledIfEnvironmentVariable(named = TestRedis.PORT_ENV, matches = "\\d+")
class RedisFakePaymentLedgerTests extends FakePaymentLedgerContract {

	private static LettuceConnectionFactory connectionFactory;
	private static RedisFakePaymentLedger ledger;

	@BeforeAll
	static void connect() {
		connectionFactory = TestRedis.connect();
		ledger = new RedisFakePaymentLedger(TestRedis.template(connectionFactory));
	}

	@AfterAll
	static void disconnect() {
		connectionFactory.destroy();
	}

	@Override
	protected FakePaymentLedger ledger() {
		return ledger;
	}

}
