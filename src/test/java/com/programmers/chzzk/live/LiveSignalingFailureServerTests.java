package com.programmers.chzzk.live;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.programmers.chzzk.TestMembers;
import com.programmers.chzzk.member.MemberRepository;

/**
 * 시그널링 중 예상하지 못한 서버 오류(DB 장애 등)가 나도 연결을 끊지 않고 안내 문구만 보내는지 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class LiveSignalingFailureServerTests {

	@LocalServerPort
	private int port;

	@MockitoBean
	private LiveBroadcastService liveBroadcastService;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void unexpectedServerFailureIsReportedWithoutClosingConnection() throws Exception {
		TestMembers.saveWithPassword(memberRepository, passwordEncoder, "failure_owner");
		given(liveBroadcastService.start(any(), anyLong())).willThrow(new DataAccessResourceFailureException("db down"));

		try (SignalingTestClient publisher = SignalingTestClient.connect(port,
			SignalingTestClient.login(port, "failure_owner", TestMembers.PASSWORD))) {
			publisher.send("{\"type\":\"start\",\"title\":\"라이브\"}");

			publisher.expectError("SERVER_ERROR");
			assertThat(publisher.isOpen()).isTrue();
		}
	}

}
