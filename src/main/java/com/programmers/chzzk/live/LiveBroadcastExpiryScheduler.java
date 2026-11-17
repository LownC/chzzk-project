package com.programmers.chzzk.live;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 생존 신호가 끊긴 라이브 방송을 주기적으로 종료하고, 그 방송의 시청자·송출자 연결에 종료를 알린다.
 * Wi-Fi가 끊긴 송출 휴대폰은 WebSocket 연결이 서버에 남아 있어 연결 종료로는 정리되지 않기 때문이다.
 * DB 종료는 서비스의 트랜잭션에서 커밋한 뒤, 트랜잭션 밖에서 알림을 보낸다.
 * 테스트는 Mock 저장소를 쓰는 경우가 있어, 별도 스레드가 끼어들지 않도록 test 프로파일에서 끈다.
 */
@Component
@ConditionalOnProperty(name = "app.live.expiry-check-enabled", havingValue = "true", matchIfMissing = true)
public class LiveBroadcastExpiryScheduler {

	private static final Logger log = LoggerFactory.getLogger(LiveBroadcastExpiryScheduler.class);

	private final LiveBroadcastService liveBroadcastService;
	private final LiveSignalingHandler signalingHandler;

	public LiveBroadcastExpiryScheduler(LiveBroadcastService liveBroadcastService,
		LiveSignalingHandler signalingHandler) {
		this.liveBroadcastService = liveBroadcastService;
		this.signalingHandler = signalingHandler;
	}

	/**
	 * 한 방송의 알림이 실패해도 나머지 방송은 계속 정리한다. 실패한 방송도 DB는 이미 ENDED다.
	 */
	@Scheduled(fixedDelayString = "${app.live.expiry-check-interval}")
	public void endExpiredBroadcasts() {
		List<Long> endedBroadcastIds = liveBroadcastService.endExpiredBroadcasts();
		for (Long broadcastId : endedBroadcastIds) {
			try {
				signalingHandler.closeEndedBroadcast(broadcastId);
			} catch (RuntimeException e) {
				log.warn("생존 신호 만료 방송의 연결 정리 실패: broadcastId={}", broadcastId, e);
			}
		}
	}

}
