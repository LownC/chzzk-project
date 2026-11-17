package com.programmers.chzzk.live;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import com.programmers.chzzk.TestRedis;

/**
 * (⑤) 여러 인스턴스가 함께 쓰는 Redis 방 등록부가 메모리 구현과 같은 규칙을 지키는지 실제 Redis로 확인한다.
 * Redis가 있어야 하므로 CHZZK_TEST_REDIS_PORT를 지정했을 때만 실행한다(README "로컬 오토스케일" 테스트 절차).
 */
@EnabledIfEnvironmentVariable(named = TestRedis.PORT_ENV, matches = "\\d+")
class RedisLiveRoomStoreTests extends LiveRoomStoreContract {

	private static LettuceConnectionFactory connectionFactory;
	private static RedisLiveRoomStore store;

	@BeforeAll
	static void connect() {
		connectionFactory = TestRedis.connect();
		store = new RedisLiveRoomStore(TestRedis.template(connectionFactory));
	}

	@AfterAll
	static void disconnect() {
		connectionFactory.destroy();
	}

	@Override
	protected LiveRoomStore store() {
		return store;
	}

}
