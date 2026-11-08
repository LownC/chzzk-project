package com.programmers.chzzk;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class TestProfileIsolationTests {

	@Autowired
	private Environment environment;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private ApplicationContext applicationContext;

	@Test
	void onlyTestProfileIsActive() {
		assertThat(environment.getActiveProfiles()).containsExactly("test");
	}

	@Test
	void connectsToInMemoryDatabase() throws SQLException {
		assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:h2:mem:testdb;MODE=MySQL");

		try (Connection connection = dataSource.getConnection();
			Statement statement = connection.createStatement();
			ResultSet mode = statement.executeQuery(
				"SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'MODE'")) {
			assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:testdb");
			assertThat(mode.next()).isTrue();
			assertThat(mode.getString(1)).isEqualTo("MySQL");
		}
	}

	@Test
	void h2ConsoleIsDisabled() {
		assertThat(environment.getProperty("spring.h2.console.enabled", Boolean.class)).isFalse();
		List<String> servletUrlMappings = applicationContext.getBeansOfType(ServletRegistrationBean.class)
			.values()
			.stream()
			.flatMap(registration -> registration.getUrlMappings().stream())
			.map(String::valueOf)
			.toList();

		assertThat(servletUrlMappings).noneMatch(mapping -> mapping.startsWith("/h2-console"));
	}

	@Test
	void uploadDirectoryIsNotDevelopmentDirectory() {
		assertThat(environment.getProperty("app.file.upload-dir")).doesNotContain("chzzk_uploads");
	}

}
