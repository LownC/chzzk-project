package com.programmers.chzzk;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * (⑤) Redis 구현 테스트용 연결. CHZZK_TEST_REDIS_PORT(와 선택적으로 CHZZK_TEST_REDIS_HOST)가 있을 때만 쓴다.
 * 예: docker run --rm -d -p 127.0.0.1:16379:6379 redis:7.4-alpine 뒤 CHZZK_TEST_REDIS_PORT=16379
 */
public final class TestRedis {

	public static final String PORT_ENV = "CHZZK_TEST_REDIS_PORT";

	private TestRedis() {
	}

	public static LettuceConnectionFactory connect() {
		String host = System.getenv().getOrDefault("CHZZK_TEST_REDIS_HOST", "127.0.0.1");
		int port = Integer.parseInt(System.getenv(PORT_ENV));
		LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port));
		factory.afterPropertiesSet();
		factory.start();
		return factory;
	}

	public static StringRedisTemplate template(LettuceConnectionFactory factory) {
		StringRedisTemplate template = new StringRedisTemplate(factory);
		template.afterPropertiesSet();
		return template;
	}

}
