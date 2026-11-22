package com.programmers.chzzk.global.health;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * (⑥) 상태 확인용 DB 연결 검사. 연결을 하나 얻어 2초 안에 유효한지만 본다. 실패 원인은 로그에만 남긴다.
 */
@Component
public class DatabaseHealthCheck {

	private static final Logger log = LoggerFactory.getLogger(DatabaseHealthCheck.class);
	private static final int VALIDATION_TIMEOUT_SECONDS = 2;

	private final DataSource dataSource;

	public DatabaseHealthCheck(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	public boolean isUp() {
		try (Connection connection = dataSource.getConnection()) {
			return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
		} catch (SQLException e) {
			log.warn("상태 확인: DB 연결 실패", e);
			return false;
		}
	}

}
