package com.programmers.chzzk.live;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.programmers.chzzk.TestRedis;
import com.programmers.chzzk.payment.RedisFakePaymentLedger;

import tools.jackson.databind.json.JsonMapper;

/**
 * (⑤) Redis에 닿지 못하면 공용 저장소는 짐작한 값(빈 방, 정원 초과, 결제 없음)을 돌려주지 않고 곧바로 실패한다.
 * 그 실패는 시그널링에서는 SERVER_ERROR 안내(LiveSignalingFailureServerTests), 화면 요청에서는 오류 화면이 된다.
 * Redis 없이 돈다(듣지 않는 포트에 연결).
 */
class RedisOutageTests {

	private static final Duration FAIL_WITHIN = Duration.ofSeconds(5);

	private static LettuceConnectionFactory connectionFactory;
	private static StringRedisTemplate redis;

	@BeforeAll
	static void connect() throws Exception {
		connectionFactory = TestRedis.unreachable();
		redis = TestRedis.template(connectionFactory);
	}

	@AfterAll
	static void disconnect() {
		connectionFactory.destroy();
	}

	@Test
	void liveRoomStoreFailsInsteadOfGuessing() {
		RedisLiveRoomStore store = new RedisLiveRoomStore(redis);

		assertTimeoutPreemptively(FAIL_WITHIN, () -> {
			assertThatThrownBy(() -> store.join(1L, "viewer-session", 4)).isInstanceOf(DataAccessException.class);
			assertThatThrownBy(() -> store.publisherOf(1L)).isInstanceOf(DataAccessException.class);
			assertThatThrownBy(() -> store.closeRoom(1L)).isInstanceOf(DataAccessException.class);
		});
	}

	@Test
	void signalRelayFailsInsteadOfDroppingSilently() {
		RedisLiveMessageRelay relay = new RedisLiveMessageRelay(redis, JsonMapper.builder().build(), null);

		assertTimeoutPreemptively(FAIL_WITHIN, () ->
			assertThatThrownBy(() -> relay.forward("other-instance-session", "{\"type\":\"offer\"}"))
				.isInstanceOf(DataAccessException.class));
	}

	@Test
	void fakePaymentLedgerNeverApprovesWithoutRedis() {
		RedisFakePaymentLedger ledger = new RedisFakePaymentLedger(redis);

		assertTimeoutPreemptively(FAIL_WITHIN, () -> {
			assertThatThrownBy(() -> ledger.confirm("fake_key", "order-1", 5000)).isInstanceOf(DataAccessException.class);
			assertThatThrownBy(() -> ledger.find("fake_key")).isInstanceOf(DataAccessException.class);
		});
	}

}
