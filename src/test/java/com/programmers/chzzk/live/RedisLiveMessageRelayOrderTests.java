package com.programmers.chzzk.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.programmers.chzzk.TestRedis;

import tools.jackson.databind.json.JsonMapper;

/**
 * (⑤) 다른 인스턴스로 넘긴 시그널링 메시지는 보낸 순서대로 전달돼야 한다. 예를 들어 방송 종료 때 서버는 시청자에게
 * "ended"를 보낸 뒤 연결을 닫는데, 순서가 바뀌면 시청자는 종료 안내 없이 끊긴다(클러스터 검증에서 실제로 확인). Offer 뒤의 ICE 후보도 같다.
 * 실제 Redis가 있어야 하므로 CHZZK_TEST_REDIS_PORT를 지정했을 때만 실행한다.
 */
@EnabledIfEnvironmentVariable(named = TestRedis.PORT_ENV, matches = "\\d+")
class RedisLiveMessageRelayOrderTests {

	private static final int MESSAGES = 200;

	private final List<String> delivered = Collections.synchronizedList(new ArrayList<>());
	private LettuceConnectionFactory connectionFactory;
	private RedisMessageListenerContainer container;
	private RedisLiveMessageRelay relay;

	@BeforeEach
	void startRelay() throws Exception {
		LiveSignalingHandler handler = mock(LiveSignalingHandler.class);
		doAnswer(invocation -> {
			// 처리 시간이 메시지마다 다르면 여러 스레드로 나눠 처리할 때 순서가 바로 드러난다.
			Thread.sleep(ThreadLocalRandom.current().nextInt(3));
			delivered.add(invocation.getArgument(1));
			return null;
		}).when(handler).deliverLocal(anyString(), anyString());
		doAnswer(invocation -> delivered.add("close")).when(handler).closeLocal(anyString());

		connectionFactory = TestRedis.connect();
		relay = new RedisLiveMessageRelay(TestRedis.template(connectionFactory), JsonMapper.builder().build(),
			new StaticListableBeanFactory(Map.of("handler", handler)).getBeanProvider(LiveSignalingHandler.class));
		container = relay.liveSignalListenerContainer(connectionFactory);
		container.afterPropertiesSet();
		container.start();
		Thread.sleep(500);
	}

	@AfterEach
	void stopRelay() throws Exception {
		container.stop();
		container.destroy();
		relay.destroy();
		connectionFactory.destroy();
	}

	@Test
	void relayedMessagesAndCloseArriveInPublishOrder() throws Exception {
		String target = "viewer-session-" + System.nanoTime();
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < MESSAGES; i++) {
			relay.forward(target, "m" + i);
			expected.add("m" + i);
		}
		relay.forwardClose(target);
		expected.add("close");

		long deadline = System.currentTimeMillis() + 10_000;
		while (delivered.size() < expected.size() && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
		}
		assertThat(delivered).containsExactlyElementsOf(expected);
	}

}
