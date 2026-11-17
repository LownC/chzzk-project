package com.programmers.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.programmers.chzzk.TestMembers;
import com.programmers.chzzk.live.LiveBroadcastService;
import com.programmers.chzzk.member.MemberRepository;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Hibernate가 enum CHECK 제약을 만든 DB 연결이 교체된 뒤에도 방송 저장이 되는지 확인한다.
 * H2 2.4.240은 CHECK(IN ...) 제약이 자신을 만든 세션을 계속 참조해서, 그 세션이 닫히면 이후 모든 INSERT·UPDATE가
 * "Check constraint invalid"(23514, 원인 90098 "The database has been closed")로 실패했다.
 * 실제 서버에서는 Hikari가 30분 수명이 지난 연결을 교체하면서 이 상태가 되었다.
 * 다른 테스트와 DB를 공유하지 않도록 이 클래스만 쓰는 메모리 DB를 쓰고, 연결이 모두 바뀌어도 DB가 남도록 DB_CLOSE_DELAY=-1을 준다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:h2-schema-retirement-regression;MODE=MySQL;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class H2SchemaConnectionRetirementTests {

	private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 18, 20, 0, 0);
	private static final long RETIREMENT_TIMEOUT_MILLIS = 10_000;

	@TempDir
	static Path uploadDirectory;

	/** 연결 교체 전에 만들어 둔 라이브 방송. 교체 뒤의 UPDATE를 확인하는 데 쓴다. */
	private static Long heartbeatTargetId;
	private static Long endTargetId;
	private static boolean schemaConnectionRetired;

	@DynamicPropertySource
	static void uploadDirectoryProperty(DynamicPropertyRegistry registry) {
		registry.add("app.file.upload-dir", uploadDirectory::toString);
	}

	@MockitoBean
	private Clock clock;

	@Autowired
	private HikariDataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private LiveBroadcastService liveBroadcastService;

	@Autowired
	private BroadcastService broadcastService;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@Autowired
	private MemberRepository memberRepository;

	private long ownerId() {
		return TestMembers.save(memberRepository, "h2_owner").getId();
	}

	@BeforeEach
	void retireSchemaCreatingConnectionOnce() throws Exception {
		given(clock.getZone()).willReturn(ZONE);
		setNow(T0);
		if (schemaConnectionRetired) {
			return;
		}
		heartbeatTargetId = liveBroadcastService.start("heartbeat 대상", ownerId()).getId();
		endTargetId = liveBroadcastService.start("종료 대상", ownerId()).getId();
		retireEveryConnectionOpenedSoFar();
		schemaConnectionRetired = true;
	}

	@Test
	void liveStartIsSavedAfterSchemaConnectionRetired() {
		Long id = liveBroadcastService.start("교체 뒤 라이브", ownerId()).getId();

		Broadcast found = broadcastRepository.findById(id).orElseThrow();
		assertThat(found.getType()).isEqualTo(BroadcastType.LIVE);
		assertThat(found.getLiveStatus()).isEqualTo(LiveStatus.LIVE);
		assertThat(found.getStartedAt()).isEqualTo(T0);
	}

	@Test
	void heartbeatIsSavedAfterSchemaConnectionRetired() {
		setNow(T0.plusMinutes(1));

		liveBroadcastService.recordHeartbeat(heartbeatTargetId);

		Broadcast found = broadcastRepository.findById(heartbeatTargetId).orElseThrow();
		assertThat(found.getLastHeartbeatAt()).isEqualTo(T0.plusMinutes(1));
		assertThat(found.getLiveStatus()).isEqualTo(LiveStatus.LIVE);
	}

	@Test
	void liveEndIsSavedAfterSchemaConnectionRetired() {
		setNow(T0.plusMinutes(2));

		assertThat(liveBroadcastService.end(endTargetId)).isTrue();

		Broadcast found = broadcastRepository.findById(endTargetId).orElseThrow();
		assertThat(found.getLiveStatus()).isEqualTo(LiveStatus.ENDED);
		assertThat(found.getEndedAt()).isEqualTo(T0.plusMinutes(2));
	}

	@Test
	void recordedRegistrationIsSavedAfterSchemaConnectionRetired() {
		MockMultipartFile video = new MockMultipartFile("videoFile", "video.mp4", "video/mp4",
			"fake mp4 bytes".getBytes(StandardCharsets.UTF_8));

		Long id = broadcastService.register("교체 뒤 녹화방송", video, ownerId());

		Broadcast found = broadcastRepository.findById(id).orElseThrow();
		assertThat(found.getType()).isEqualTo(BroadcastType.RECORDED);
		assertThat(found.getLiveStatus()).isNull();
		assertThat(Files.exists(uploadDirectory.resolve(found.getVideoFileName()))).isTrue();
	}

	/**
	 * 제약이 계속 동작해서 허용되지 않은 방송 종류는 막아야 한다. 23513은 CHECK 제약 위반이고, 버그 상태의 23514와 다르다.
	 */
	@Test
	void unknownBroadcastTypeIsStillRejectedByCheckConstraint() {
		long countBefore = broadcastRepository.count();

		assertThatThrownBy(() -> jdbcTemplate.update(
			"INSERT INTO broadcast (created_at, title, broadcast_type) VALUES (CURRENT_TIMESTAMP, '잘못된 종류', 'BOGUS')"))
			.isInstanceOf(DataIntegrityViolationException.class)
			.cause()
			.isInstanceOfSatisfying(SQLException.class, cause -> assertThat(cause.getSQLState()).isEqualTo("23513"));
		assertThat(broadcastRepository.count()).isEqualTo(countBefore);
	}

	/**
	 * 지금까지 열린 연결을 모두 교체한다. Hibernate가 스키마를 만든 연결도 이 안에 있다.
	 * H2 세션 번호는 늘어나기만 하므로, 남은 세션 번호가 모두 교체 전 최댓값보다 크면 그 연결은 닫힌 것이다.
	 * Hikari는 연결을 다른 스레드에서 닫으므로 바뀔 때까지 기다린다.
	 */
	private void retireEveryConnectionOpenedSoFar() throws InterruptedException {
		List<Long> sessionsBefore = sessionIds();
		assertThat(sessionsBefore)
			.as("sa 계정이 다른 연결의 세션까지 볼 수 있어야 교체 여부를 판단할 수 있다")
			.hasSizeGreaterThan(1);
		long newestSessionBefore = sessionsBefore.stream().mapToLong(Long::longValue).max().orElseThrow();

		dataSource.getHikariPoolMXBean().softEvictConnections();

		long deadline = System.currentTimeMillis() + RETIREMENT_TIMEOUT_MILLIS;
		while (oldestSessionId() <= newestSessionBefore) {
			assertThat(System.currentTimeMillis())
				.as("교체 전 세션이 %d ms 안에 모두 닫혀야 한다", RETIREMENT_TIMEOUT_MILLIS)
				.isLessThan(deadline);
			Thread.sleep(50);
		}
	}

	private List<Long> sessionIds() {
		return jdbcTemplate.queryForList("SELECT SESSION_ID FROM INFORMATION_SCHEMA.SESSIONS", Long.class);
	}

	private long oldestSessionId() {
		return sessionIds().stream().mapToLong(Long::longValue).min().orElseThrow();
	}

	private void setNow(LocalDateTime now) {
		Instant instant = now.atZone(ZONE).toInstant();
		given(clock.instant()).willReturn(instant);
		given(clock.millis()).willReturn(instant.toEpochMilli());
	}

}
