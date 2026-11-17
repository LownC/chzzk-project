package com.programmers.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BroadcastRepositoryTests {

	@Autowired
	private BroadcastRepository broadcastRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void savedBroadcastGetsCreatedAtAutomatically() {
		LocalDateTime before = LocalDateTime.now();

		Broadcast saved = broadcastRepository.save(
			new Broadcast("첫 방송", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4"));

		LocalDateTime after = LocalDateTime.now();
		Broadcast found = broadcastRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getTitle()).isEqualTo("첫 방송");
		assertThat(found.getVideoFileName()).isEqualTo("3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4");
		assertThat(found.getCreatedAt()).isBetween(before, after);
	}

	/**
	 * 1단계에서 저장한 행처럼 방송 종류 없이 넣은 행은 DB 기본값으로 녹화방송이 된다.
	 */
	@Test
	void rowInsertedWithoutTypeIsRecordedBroadcast() {
		jdbcTemplate.update("insert into broadcast (title, video_file_name, created_at) values (?, ?, ?)",
			"1단계 방송", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4", LocalDateTime.of(2026, 11, 1, 10, 0));

		Broadcast found = broadcastRepository.findAll().getFirst();
		assertThat(found.getType()).isEqualTo(BroadcastType.RECORDED);
		assertThat(found.getLiveStatus()).isNull();
	}

	@Test
	void liveBroadcastIsSavedWithoutVideoFile() {
		LocalDateTime startedAt = LocalDateTime.of(2026, 11, 18, 20, 0);

		Broadcast saved = broadcastRepository.saveAndFlush(Broadcast.startLive("라이브", startedAt));

		Broadcast found = broadcastRepository.findById(saved.getId()).orElseThrow();
		assertThat(found.getType()).isEqualTo(BroadcastType.LIVE);
		assertThat(found.getLiveStatus()).isEqualTo(LiveStatus.LIVE);
		assertThat(found.getVideoFileName()).isNull();
		assertThat(found.getStartedAt()).isEqualTo(startedAt);
	}

	@Test
	void findsOnlyLiveBroadcastsWithOldHeartbeat() {
		LocalDateTime now = LocalDateTime.of(2026, 11, 18, 20, 0);
		Broadcast stale = broadcastRepository.save(Broadcast.startLive("오래된 신호", now.minusSeconds(31)));
		broadcastRepository.save(Broadcast.startLive("최근 신호", now.minusSeconds(5)));
		Broadcast ended = Broadcast.startLive("종료됨", now.minusMinutes(5));
		ended.endLive(now.minusMinutes(4));
		broadcastRepository.save(ended);

		assertThat(broadcastRepository.findByLiveStatusAndLastHeartbeatAtBefore(LiveStatus.LIVE, now.minusSeconds(30)))
			.extracting(Broadcast::getId)
			.containsExactly(stale.getId());
	}

}
