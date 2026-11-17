package com.programmers.chzzk.broadcast;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long> {

	/**
	 * 송출자의 생존 신호가 끊긴 라이브 방송을 찾는다(서버 재시작·연결 불일치 복구).
	 */
	List<Broadcast> findByLiveStatusAndLastHeartbeatAtBefore(LiveStatus liveStatus, LocalDateTime threshold);

}
