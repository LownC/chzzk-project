package com.programmers.chzzk.global.health;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * (⑥) 배포 서버 상태 확인 경로. 정상이면 200 {"status":"UP"}, DB에 닿지 못하면 503 {"status":"DOWN"}.
 * 내부 예외·DB 경로는 응답에 넣지 않는다. GET 요청은 SecurityConfig에서 이미 공개라 별도 설정이 필요 없다.
 */
@RestController
public class HealthController {

	private final DatabaseHealthCheck databaseHealthCheck;

	public HealthController(DatabaseHealthCheck databaseHealthCheck) {
		this.databaseHealthCheck = databaseHealthCheck;
	}

	@GetMapping("/health")
	public ResponseEntity<Map<String, String>> health() {
		if (databaseHealthCheck.isUp()) {
			return ResponseEntity.ok(Map.of("status", "UP"));
		}
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
	}

}
