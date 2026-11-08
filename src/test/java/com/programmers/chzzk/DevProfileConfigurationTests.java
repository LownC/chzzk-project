package com.programmers.chzzk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;

/**
 * 애플리케이션 컨텍스트를 띄우지 않고 설정 파일만 읽어 기본 실행 시의 dev 프로파일 설정을 확인한다.
 * 데이터소스를 만들지 않으므로 개발용 H2 파일 DB에 연결하지 않는다.
 * Gradle이 테스트 JVM에 넣는 spring.profiles.active=test의 영향을 받지 않도록
 * 시스템 속성과 OS 환경 변수를 제외하고 설정 파일만으로 판단한다.
 */
class DevProfileConfigurationTests {

	private StandardEnvironment environment;

	@BeforeEach
	void loadDefaultConfiguration() {
		environment = new StandardEnvironment();
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		ConfigDataEnvironmentPostProcessor.applyTo(environment);
	}

	@Test
	void devProfileIsActivatedByDefault() {
		assertThat(environment.getActiveProfiles()).isEmpty();
		assertThat(environment.getDefaultProfiles()).containsExactly("dev");
	}

	@Test
	void devProfileUsesFileDatabase() {
		assertThat(environment.getProperty("spring.datasource.url"))
			.startsWith("jdbc:h2:file:")
			.endsWith("/chzzk_db;MODE=MySQL");
	}

	@Test
	void h2ConsoleIsEnabledInDevProfile() {
		assertThat(environment.getProperty("spring.h2.console.enabled", Boolean.class)).isTrue();
	}

}
