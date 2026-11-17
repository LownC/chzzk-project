package com.programmers.chzzk.live;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.broadcast.BroadcastRegistrationError;
import com.programmers.chzzk.broadcast.BroadcastRegistrationException;
import com.programmers.chzzk.broadcast.BroadcastRepository;
import com.programmers.chzzk.broadcast.BroadcastType;
import com.programmers.chzzk.broadcast.LiveStatus;

/**
 * 라이브 방송의 상태(LIVE/ENDED)를 DB에 기록한다. 실제 WebSocket 연결은 {@link LiveSignalingHandler}가 관리한다.
 */
@Service
public class LiveBroadcastService {

	private static final Logger log = LoggerFactory.getLogger(LiveBroadcastService.class);

	private static final int MAX_TITLE_LENGTH = 100;

	private final BroadcastRepository broadcastRepository;
	private final Clock clock;
	private final LiveProperties liveProperties;

	public LiveBroadcastService(BroadcastRepository broadcastRepository, Clock clock, LiveProperties liveProperties) {
		this.broadcastRepository = broadcastRepository;
		this.clock = clock;
		this.liveProperties = liveProperties;
	}

	/**
	 * 제목은 녹화방송 등록과 같은 규칙으로 검증한다.
	 */
	@Transactional
	public Broadcast start(String title) {
		if (title == null || title.isBlank()) {
			throw new BroadcastRegistrationException(BroadcastRegistrationError.TITLE_REQUIRED);
		}
		String strippedTitle = title.strip();
		if (strippedTitle.length() > MAX_TITLE_LENGTH) {
			throw new BroadcastRegistrationException(BroadcastRegistrationError.TITLE_TOO_LONG);
		}
		Broadcast broadcast = broadcastRepository.save(Broadcast.startLive(strippedTitle, now()));
		log.info("라이브 시작: broadcastId={}", broadcast.getId());
		return broadcast;
	}

	@Transactional(readOnly = true)
	public Optional<Broadcast> findBroadcast(long id) {
		return broadcastRepository.findById(id);
	}

	/**
	 * 라이브 방송을 종료한다. 없는 방송, 녹화방송, 이미 종료된 방송이면 false다.
	 */
	@Transactional
	public boolean end(long id) {
		boolean ended = broadcastRepository.findById(id)
			.filter(broadcast -> broadcast.getType() == BroadcastType.LIVE)
			.map(broadcast -> broadcast.endLive(now()))
			.orElse(false);
		if (ended) {
			log.info("라이브 종료: broadcastId={}", id);
		}
		return ended;
	}

	/**
	 * 종료된 방송이나 없는 방송이면 {@link IllegalStateException}이다. 호출한 쪽은 송출을 끝내야 한다.
	 */
	@Transactional
	public void recordHeartbeat(long id) {
		Broadcast broadcast = broadcastRepository.findById(id)
			.orElseThrow(() -> new IllegalStateException("없는 방송입니다: id=" + id));
		broadcast.recordHeartbeat(now());
	}

	/**
	 * 생존 신호가 끊긴 라이브 방송을 종료한다. 서버가 재시작되어 송출 연결이 모두 사라진 경우도 이 처리로 정리된다.
	 * 서버 시작 시 LIVE를 일괄 종료하지 않는 이유는 여러 서버가 같은 DB를 쓸 때 다른 서버의 방송까지 끝내기 때문이다.
	 * 주기 실행은 {@link LiveBroadcastExpiryScheduler}가 맡는다.
	 */
	@Transactional
	public List<Long> endExpiredBroadcasts() {
		LocalDateTime now = now();
		List<Broadcast> expired = broadcastRepository.findByLiveStatusAndLastHeartbeatAtBefore(
			LiveStatus.LIVE, now.minus(liveProperties.heartbeatTimeout()));
		for (Broadcast broadcast : expired) {
			broadcast.endLive(now);
			log.info("생존 신호가 끊겨 라이브 종료: broadcastId={}", broadcast.getId());
		}
		return expired.stream().map(Broadcast::getId).toList();
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

}
