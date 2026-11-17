package com.programmers.chzzk.live;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인스턴스 사이 시그널링 중계(⑤ cluster 프로파일). 받는 연결이 다른 인스턴스에 있으면 Redis 채널
 * {@value #CHANNEL}에 {@code {"target": 세션 ID, "payload": 메시지}} 또는 {@code {"target": ..., "close": true}}를
 * 발행하고, 모든 인스턴스가 구독하다가 그 연결을 가진 인스턴스만 전달·종료한다. WebSocket 세션 ID는 UUID라 인스턴스 사이에서 겹치지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "app.cluster.enabled", havingValue = "true")
public class RedisLiveMessageRelay implements LiveMessageRelay, MessageListener {

	private static final Logger log = LoggerFactory.getLogger(RedisLiveMessageRelay.class);

	static final String CHANNEL = "chzzk:live:signal";

	private final StringRedisTemplate redis;
	private final JsonMapper jsonMapper;
	private final ObjectProvider<LiveSignalingHandler> handler;

	public RedisLiveMessageRelay(StringRedisTemplate redis, JsonMapper jsonMapper,
		ObjectProvider<LiveSignalingHandler> handler) {
		this.redis = redis;
		this.jsonMapper = jsonMapper;
		this.handler = handler;
	}

	@Bean
	public RedisMessageListenerContainer liveSignalListenerContainer(RedisConnectionFactory connectionFactory) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		container.addMessageListener(this, new ChannelTopic(CHANNEL));
		return container;
	}

	@Override
	public void forward(String sessionId, String payload) {
		redis.convertAndSend(CHANNEL, jsonMapper.createObjectNode().put("target", sessionId).put("payload", payload).toString());
	}

	@Override
	public void forwardClose(String sessionId) {
		redis.convertAndSend(CHANNEL, jsonMapper.createObjectNode().put("target", sessionId).put("close", true).toString());
	}

	@Override
	public void onMessage(Message message, byte[] pattern) {
		JsonNode relayed;
		try {
			relayed = jsonMapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
		} catch (JacksonException e) {
			log.warn("시그널링 중계 메시지 해석 실패");
			return;
		}
		String target = relayed.path("target").asString("");
		LiveSignalingHandler localHandler = handler.getObject();
		if (relayed.path("close").asBoolean(false)) {
			localHandler.closeLocal(target);
		} else {
			localHandler.deliverLocal(target, relayed.path("payload").asString(""));
		}
	}

}
