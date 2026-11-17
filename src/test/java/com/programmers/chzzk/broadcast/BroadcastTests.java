package com.programmers.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.programmers.chzzk.member.Member;

/**
 * 라이브 방송 상태 전이 규칙(docs/live-streaming.md 3절)을 도메인 객체만으로 확인한다.
 */
class BroadcastTests {

	private static final Member OWNER = new Member("owner", "unused-password-hash");

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 18, 20, 0, 0);
	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	@Test
	void recordedBroadcastHasNoLiveState() {
		Broadcast recorded = new Broadcast("녹화 방송", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4");

		assertThat(recorded.getType()).isEqualTo(BroadcastType.RECORDED);
		assertThat(recorded.getLiveStatus()).isNull();
		assertThat(recorded.isLiveNow()).isFalse();
	}

	@Test
	void startedLiveBroadcastIsLiveWithoutVideoFile() {
		Broadcast live = Broadcast.startLive("라이브 방송", OWNER, NOW);

		assertThat(live.getType()).isEqualTo(BroadcastType.LIVE);
		assertThat(live.getLiveStatus()).isEqualTo(LiveStatus.LIVE);
		assertThat(live.getVideoFileName()).isNull();
		assertThat(live.getStartedAt()).isEqualTo(NOW);
		assertThat(live.getLastHeartbeatAt()).isEqualTo(NOW);
		assertThat(live.getEndedAt()).isNull();
		assertThat(live.isLiveNow()).isTrue();
	}

	@Test
	void heartbeatExtendsLiveBroadcast() {
		Broadcast live = Broadcast.startLive("라이브 방송", OWNER, NOW);

		live.recordHeartbeat(NOW.plusSeconds(10));

		assertThat(live.getLastHeartbeatAt()).isEqualTo(NOW.plusSeconds(10));
		assertThat(live.isHeartbeatExpired(NOW.plusSeconds(39), TIMEOUT)).isFalse();
		assertThat(live.isHeartbeatExpired(NOW.plusSeconds(41), TIMEOUT)).isTrue();
	}

	@Test
	void endingLiveBroadcastHappensOnlyOnce() {
		Broadcast live = Broadcast.startLive("라이브 방송", OWNER, NOW);

		assertThat(live.endLive(NOW.plusMinutes(5))).isTrue();
		assertThat(live.endLive(NOW.plusMinutes(9))).isFalse();

		assertThat(live.getLiveStatus()).isEqualTo(LiveStatus.ENDED);
		assertThat(live.getEndedAt()).isEqualTo(NOW.plusMinutes(5));
		assertThat(live.isLiveNow()).isFalse();
	}

	@Test
	void endedBroadcastRejectsHeartbeat() {
		Broadcast live = Broadcast.startLive("라이브 방송", OWNER, NOW);
		live.endLive(NOW.plusMinutes(1));

		assertThatThrownBy(() -> live.recordHeartbeat(NOW.plusMinutes(2)))
			.isInstanceOf(IllegalStateException.class);
		assertThat(live.getLastHeartbeatAt()).isEqualTo(NOW);
	}

	@Test
	void endedBroadcastIsNeverConsideredExpiredAgain() {
		Broadcast live = Broadcast.startLive("라이브 방송", OWNER, NOW);
		live.endLive(NOW.plusMinutes(1));

		assertThat(live.isHeartbeatExpired(NOW.plusHours(1), TIMEOUT)).isFalse();
	}

	@Test
	void recordedBroadcastRejectsLiveOperations() {
		Broadcast recorded = new Broadcast("녹화 방송", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4");

		assertThatThrownBy(() -> recorded.endLive(NOW)).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> recorded.recordHeartbeat(NOW)).isInstanceOf(IllegalStateException.class);
		assertThat(recorded.isHeartbeatExpired(NOW.plusHours(1), TIMEOUT)).isFalse();
	}

}
