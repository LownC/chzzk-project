package com.programmers.chzzk.broadcast;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BroadcastRepository extends JpaRepository<Broadcast, Long> {

	/**
	 * 상세 화면에 소유자 이름을 보여 주므로 소유자를 함께 읽는다.
	 */
	@Override
	@EntityGraph(attributePaths = "owner")
	Optional<Broadcast> findById(Long id);

	/**
	 * 송출자의 생존 신호가 끊긴 라이브 방송을 찾는다(서버 재시작·연결 불일치 복구).
	 */
	List<Broadcast> findByLiveStatusAndLastHeartbeatAtBefore(LiveStatus liveStatus, LocalDateTime threshold);

}
