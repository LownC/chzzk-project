package com.programmers.chzzk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * 휴대폰 카메라용 개발 HTTPS 프로파일 설정을 확인한다. 실제 키스토어는 저장소에 없으므로 경로·비밀번호는
 * 값이 아닌 환경변수 자리표시자로만 있어야 한다.
 */
class HttpsProfileConfigurationTests {

	private StandardEnvironment environment;

	@BeforeEach
	void loadHttpsConfiguration() {
		environment = new StandardEnvironment();
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		environment.setActiveProfiles("dev", "https");
		ConfigDataEnvironmentPostProcessor.applyTo(environment);
	}

	@Test
	void httpsProfileEnablesTlsOnPort8443() {
		assertThat(environment.getProperty("server.ssl.enabled", Boolean.class)).isTrue();
		assertThat(environment.getProperty("server.port")).isEqualTo("8443");
		assertThat(environment.getProperty("server.ssl.key-store-type")).isEqualTo("PKCS12");
		assertThat(environment.getProperty("server.ssl.key-alias")).isEqualTo("chzzk-dev");
	}

	@Test
	void keyStoreLocationAndPasswordComeOnlyFromEnvironmentVariables() {
		environment.getPropertySources().addFirst(new MapPropertySource("environmentVariables", Map.of(
			"DEV_SSL_KEY_STORE", "C:/certs/test.p12",
			"DEV_SSL_KEY_STORE_PASSWORD", "from-environment")));

		assertThat(environment.getProperty("server.ssl.key-store")).isEqualTo("C:/certs/test.p12");
		assertThat(environment.getProperty("server.ssl.key-store-password")).isEqualTo("from-environment");
	}

	@Test
	void devProfileAloneDoesNotEnableTls() {
		StandardEnvironment devOnly = new StandardEnvironment();
		devOnly.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		devOnly.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		ConfigDataEnvironmentPostProcessor.applyTo(devOnly);

		assertThat(devOnly.getProperty("server.ssl.enabled")).isNull();
	}

}
