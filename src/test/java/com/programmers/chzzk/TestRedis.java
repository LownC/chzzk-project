package com.programmers.chzzk;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
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

	/**
	 * 아무것도 듣고 있지 않은 포트를 가리키는 연결(Redis 장애 흉내). Redis 없이도 쓸 수 있다.
	 */
	public static LettuceConnectionFactory unreachable() throws IOException {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		LettuceClientConfiguration client = LettuceClientConfiguration.builder()
			.commandTimeout(Duration.ofSeconds(2))
			.build();
		LettuceConnectionFactory factory = new LettuceConnectionFactory(
			new RedisStandaloneConfiguration("127.0.0.1", closedPort), client);
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
