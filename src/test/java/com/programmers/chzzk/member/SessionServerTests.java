package com.programmers.chzzk.member;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import com.programmers.chzzk.TestHttpLogin;
import com.programmers.chzzk.TestMembers;

/**
 * 실제 서버(Tomcat 세션)에서 세션 고정 방어, 만료·알 수 없는 세션, 로그아웃 뒤 세션 무효화를 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SessionServerTests {

	private static final String PROTECTED_PAGE = "/broadcasts/new";

	@LocalServerPort
	private int port;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Value("${server.servlet.session.timeout}")
	private Duration sessionTimeout;

	@Test
	void sessionIdBeforeLoginIsNotAuthenticatedAfterLogin() throws Exception {
		TestMembers.saveWithPassword(memberRepository, passwordEncoder, "session_fixation");

		String[] cookies = TestHttpLogin.loginKeepingPreLoginCookie(port, "session_fixation", TestMembers.PASSWORD);

		assertThat(cookies[1]).isNotEqualTo(cookies[0]);
		assertThat(TestHttpLogin.get(port, PROTECTED_PAGE, cookies[1]).statusCode()).isEqualTo(200);
		assertRedirectedToLogin(TestHttpLogin.get(port, PROTECTED_PAGE, cookies[0]));
	}

	/**
	 * 만료되었거나 서버가 모르는 세션 쿠키는 로그인하지 않은 요청으로 처리된다.
	 */
	@Test
	void expiredOrUnknownSessionIsTreatedAsAnonymous() throws Exception {
		assertRedirectedToLogin(TestHttpLogin.get(port, PROTECTED_PAGE, "JSESSIONID=0123456789ABCDEF0123456789ABCDEF"));
		assertThat(sessionTimeout).isEqualTo(Duration.ofMinutes(30));
	}

	@Test
	void logoutInvalidatesServerSession() throws Exception {
		TestMembers.saveWithPassword(memberRepository, passwordEncoder, "session_logout");
		String cookie = TestHttpLogin.login(port, "session_logout", TestMembers.PASSWORD);
		String csrfToken = TestHttpLogin.csrfTokenFromRegistrationPage(port, cookie);

		HttpResponse<String> logout = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/logout"))
				.header("Cookie", cookie)
				.header("X-CSRF-TOKEN", csrfToken)
				.POST(HttpRequest.BodyPublishers.noBody())
				.build(),
			HttpResponse.BodyHandlers.ofString());

		assertThat(logout.statusCode()).isEqualTo(302);
		assertThat(logout.headers().firstValue("Location")).hasValueSatisfying(
			location -> assertThat(location).endsWith("/login?logout"));
		assertRedirectedToLogin(TestHttpLogin.get(port, PROTECTED_PAGE, cookie));
	}

	private static void assertRedirectedToLogin(HttpResponse<String> response) {
		assertThat(response.statusCode()).isEqualTo(302);
		assertThat(response.headers().firstValue("Location")).hasValueSatisfying(
			location -> assertThat(location).endsWith("/login"));
	}

}
