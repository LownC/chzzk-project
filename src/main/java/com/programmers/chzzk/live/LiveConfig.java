package com.programmers.chzzk.live;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 라이브 시그널링 WebSocket(/ws/live), 생존 신호 점검 스케줄, 라이브 설정을 켠다.
 * 허용 출처를 따로 지정하지 않으므로 WebSocket 핸드셰이크는 같은 출처만 허용된다.
 */
@Configuration
@EnableWebSocket
@EnableScheduling
@EnableConfigurationProperties(LiveProperties.class)
public class LiveConfig implements WebSocketConfigurer {

	private final LiveSignalingHandler liveSignalingHandler;

	public LiveConfig(LiveSignalingHandler liveSignalingHandler) {
		this.liveSignalingHandler = liveSignalingHandler;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(liveSignalingHandler, "/ws/live");
	}

	/**
	 * static이라 이 설정 객체(시그널링 처리기를 주입받음)를 만들기 전에 생성된다. 처리기 → 서비스 → Clock 순환을 막는다.
	 */
	@Bean
	public static Clock clock() {
		return Clock.systemDefaultZone();
	}

}
