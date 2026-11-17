package com.programmers.chzzk.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.programmers.chzzk.TestMembers;
import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.broadcast.BroadcastRegistrationError;
import com.programmers.chzzk.broadcast.BroadcastRegistrationException;
import com.programmers.chzzk.broadcast.BroadcastRepository;
import com.programmers.chzzk.broadcast.LiveStatus;
import com.programmers.chzzk.global.exception.NotFoundException;
import com.programmers.chzzk.member.MemberRepository;

@SpringBootTest
@ActiveProfiles("test")
class LiveBroadcastServiceTests {

	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 18, 20, 0, 0);

	@MockitoBean
	private Clock clock;

	@Autowired
	private LiveBroadcastService liveBroadcastService;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@Autowired
	private MemberRepository memberRepository;

	private long ownerId;

	@BeforeEach
	void setUp() {
		broadcastRepository.deleteAll();
		ownerId = TestMembers.save(memberRepository, "service_owner").getId();
		given(clock.getZone()).willReturn(ZONE);
		setNow(T0);
	}

	@Test
	void startSavesLiveBroadcastWithStrippedTitle() {
		Broadcast started = liveBroadcastService.start("  휴대폰 라이브  ", ownerId);

		Broadcast found = broadcastRepository.findById(started.getId()).orElseThrow();
		assertThat(found.getTitle()).isEqualTo("휴대폰 라이브");
		assertThat(found.getLiveStatus()).isEqualTo(LiveStatus.LIVE);
		assertThat(found.getStartedAt()).isEqualTo(T0);
		assertThat(found.getLastHeartbeatAt()).isEqualTo(T0);
	}

	@Test
	void startRejectsBlankOrTooLongTitle() {
		assertThatThrownBy(() -> liveBroadcastService.start("   ", ownerId))
			.isInstanceOfSatisfying(BroadcastRegistrationException.class,
				exception -> assertThat(exception.getError()).isEqualTo(BroadcastRegistrationError.TITLE_REQUIRED));
		assertThatThrownBy(() -> liveBroadcastService.start("가".repeat(101), ownerId))
			.isInstanceOfSatisfying(BroadcastRegistrationException.class,
				exception -> assertThat(exception.getError()).isEqualTo(BroadcastRegistrationError.TITLE_TOO_LONG));
		assertThat(broadcastRepository.count()).isZero();
	}

	@Test
	void endMarksBroadcastEndedOnlyOnce() {
		Long id = liveBroadcastService.start("라이브", ownerId).getId();
		setNow(T0.plusMinutes(3));

		assertThat(liveBroadcastService.end(id)).isTrue();
		setNow(T0.plusMinutes(9));
		assertThat(liveBroadcastService.end(id)).isFalse();

		Broadcast found = broadcastRepository.findById(id).orElseThrow();
		assertThat(found.getLiveStatus()).isEqualTo(LiveStatus.ENDED);
		assertThat(found.getEndedAt()).isEqualTo(T0.plusMinutes(3));
	}

	@Test
	void endIgnoresMissingAndRecordedBroadcasts() {
		Long recordedId = broadcastRepository.save(
			new Broadcast("녹화", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4")).getId();

		assertThat(liveBroadcastService.end(recordedId)).isFalse();
		assertThat(liveBroadcastService.end(9_999L)).isFalse();
	}

	@Test
	void heartbeatUpdatesLiveBroadcastAndIsRejectedAfterEnd() {
		Long id = liveBroadcastService.start("라이브", ownerId).getId();
		setNow(T0.plusSeconds(10));

		liveBroadcastService.recordHeartbeat(id);
		assertThat(broadcastRepository.findById(id).orElseThrow().getLastHeartbeatAt()).isEqualTo(T0.plusSeconds(10));

		liveBroadcastService.end(id);
		assertThatThrownBy(() -> liveBroadcastService.recordHeartbeat(id)).isInstanceOf(IllegalStateException.class);
	}

	/**
	 * 서버 재시작 등으로 송출자 연결이 사라져 heartbeat가 끊긴 방송만 종료된다.
	 */
	@Test
	void expiredBroadcastsAreEndedAndFreshOnesStayLive() {
		Long staleId = liveBroadcastService.start("신호 끊김", ownerId).getId();
		setNow(T0.plusSeconds(25));
		Long freshId = liveBroadcastService.start("신호 정상", ownerId).getId();

		setNow(T0.plusSeconds(31));
		assertThat(liveBroadcastService.endExpiredBroadcasts()).containsExactly(staleId);

		Broadcast stale = broadcastRepository.findById(staleId).orElseThrow();
		assertThat(stale.getLiveStatus()).isEqualTo(LiveStatus.ENDED);
		assertThat(stale.getEndedAt()).isEqualTo(T0.plusSeconds(31));
		assertThat(broadcastRepository.findById(freshId).orElseThrow().getLiveStatus()).isEqualTo(LiveStatus.LIVE);
	}

	@Test
	void ownerCanEndLiveBroadcastButOtherMemberCannot() {
		Long id = liveBroadcastService.start("라이브", ownerId).getId();
		long otherId = TestMembers.save(memberRepository, "service_other").getId();

		assertThatThrownBy(() -> liveBroadcastService.endByOwner(id, otherId))
			.isInstanceOf(AccessDeniedException.class);
		assertThat(broadcastRepository.findById(id).orElseThrow().getLiveStatus()).isEqualTo(LiveStatus.LIVE);

		setNow(T0.plusMinutes(1));
		assertThat(liveBroadcastService.endByOwner(id, ownerId)).isTrue();
		assertThat(broadcastRepository.findById(id).orElseThrow().getLiveStatus()).isEqualTo(LiveStatus.ENDED);
		assertThat(liveBroadcastService.endByOwner(id, ownerId)).isFalse();
	}

	@Test
	void endingMissingOrRecordedBroadcastIsNotFound() {
		Long recordedId = broadcastRepository.save(
			new Broadcast("녹화", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4")).getId();

		assertThatThrownBy(() -> liveBroadcastService.endByOwner(9_999L, ownerId))
			.isInstanceOf(NotFoundException.class);
		assertThatThrownBy(() -> liveBroadcastService.endByOwner(recordedId, ownerId))
			.isInstanceOf(NotFoundException.class);
	}

	private void setNow(LocalDateTime now) {
		Instant instant = now.atZone(ZONE).toInstant();
		given(clock.instant()).willReturn(instant);
		given(clock.millis()).willReturn(instant.toEpochMilli());
	}

}
