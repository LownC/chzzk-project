package com.programmers.chzzk.global.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * (⑥) 상태 확인 경로 /health.
 */
class HealthCheckTests {

	@Nested
	@SpringBootTest
	@AutoConfigureMockMvc
	@ActiveProfiles("test")
	class WhenDatabaseIsReachable {

		@Autowired
		private MockMvc mockMvc;

		@Test
		void anonymousRequestGetsUp() throws Exception {
			mockMvc.perform(get("/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
		}
	}

	@Nested
	@SpringBootTest
	@AutoConfigureMockMvc
	@ActiveProfiles("test")
	class WhenDatabaseIsDown {

		@Autowired
		private MockMvc mockMvc;

		@MockitoBean
		private DatabaseHealthCheck databaseHealthCheck;

		@Test
		void returnsServiceUnavailableWithoutInternalDetails() throws Exception {
			given(databaseHealthCheck.isUp()).willReturn(false);

			mockMvc.perform(get("/health"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value("DOWN"))
				.andExpect(content().string(not(containsString("jdbc"))))
				.andExpect(content().string(not(containsString("Exception"))));
		}
	}

	@Test
	void connectionFailureIsReportedAsDown() throws SQLException {
		DataSource dataSource = mock(DataSource.class);
		given(dataSource.getConnection()).willThrow(new SQLException("connection refused"));

		assertThat(new DatabaseHealthCheck(dataSource).isUp()).isFalse();
	}

}
