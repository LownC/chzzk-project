package com.programmers.chzzk.live;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 라이브 방송 설정.
 *
 * @param maxViewers       방송당 동시 시청자 수. P2P에서는 송출 휴대폰이 시청자마다 영상을 따로 보내므로 작게 둔다.
 * @param heartbeatTimeout 송출자 생존 신호가 이 시간보다 오래 없으면 방송을 종료한다.
 * @param iceServers       WebRTC STUN/TURN 서버 URL. 같은 Wi-Fi에서는 비워 둔다.
 */
@ConfigurationProperties("app.live")
public record LiveProperties(int maxViewers, Duration heartbeatTimeout, List<String> iceServers) {

	public LiveProperties {
		iceServers = iceServers == null ? List.of() : iceServers.stream().filter(url -> !url.isBlank()).toList();
	}

}
