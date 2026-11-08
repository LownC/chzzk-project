package com.programmers.chzzk;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;

/**
 * Gradle 테스트 태스크가 테스트 JVM에 넣는 설정을 확인한다.
 * {@code @ActiveProfiles} 없이 설정 파일만 읽으므로 데이터소스를 만들지 않는다.
 */
class TestRuntimeEnvironmentTests {

	@Test
	void testProfileIsForcedWithoutActiveProfilesAnnotation() {
		StandardEnvironment environment = new StandardEnvironment();
		ConfigDataEnvironmentPostProcessor.applyTo(environment);

		assertThat(environment.getActiveProfiles()).containsExactly("test");
		assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:h2:mem:testdb;MODE=MySQL");
	}

	@Test
	void jvmDefaultTimeZoneIsSeoul() {
		ZoneId seoul = ZoneId.of(ChzzkApplication.DEFAULT_TIME_ZONE);

		assertThat(TimeZone.getDefault().getID()).isEqualTo(ChzzkApplication.DEFAULT_TIME_ZONE);
		assertThat(ZoneId.systemDefault()).isEqualTo(seoul);
		assertThat(Duration.between(LocalDateTime.now(seoul), LocalDateTime.now()).abs())
			.isLessThan(Duration.ofSeconds(5));
	}

}
