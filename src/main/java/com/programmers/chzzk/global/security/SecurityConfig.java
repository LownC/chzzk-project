package com.programmers.chzzk.global.security;

import java.io.IOException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 세션 기반 폼 로그인. 공개 범위와 로그인이 필요한 요청은 README "인증·인가 정책"에 정리했다.
 * <ul>
 *     <li>공개(GET): 방송 목록·상세(댓글 목록 포함)·영상 재생·라이브 시청(시그널링 연결), 로그인·회원가입 화면</li>
 *     <li>로그인 필요: 방송 등록·라이브 송출·제목 수정 화면, 그리고 모든 POST(방송 등록·수정·삭제·종료,
 *     댓글 작성·수정·삭제, 로그아웃). 로그인·회원가입 POST만 예외다.</li>
 *     <li>라이브 방송 시작은 시그널링 처리기가 연결의 로그인 정보로 다시 검사한다.</li>
 *     <li>(④) 후원 결제 흐름은 로그인 필요, 주문은 주문한 회원만. 결제 서비스 승인 통지 주소만 로그인·CSRF 검사 예외다.</li>
 * </ul>
 * 소유자·작성자 검사는 URL 규칙이 아니라 각 서비스가 회원 ID로 한다. CSRF 보호는 켜 둔다.
 * XHR 요청(업로드)은 로그인 화면으로 보내지 않고 401·403 JSON으로 응답한다.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private static final RequestMatcher XHR_REQUEST = new RequestHeaderRequestMatcher("X-Requested-With", "XMLHttpRequest");
	private static final String PAYMENT_WEBHOOK = "/payments/toss/webhook";
	private static final LoginUrlAuthenticationEntryPoint LOGIN_PAGE = new LoginUrlAuthenticationEntryPoint("/login");
	private static final AccessDeniedHandlerImpl ACCESS_DENIED_PAGE = new AccessDeniedHandlerImpl();

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	@Order(2)
	public SecurityFilterChain applicationSecurityFilterChain(HttpSecurity http) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
				.dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()
				// 등록·송출·수정 화면과 후원 결제 흐름(결제 화면·결제창이 돌아오는 주소·결과)은 로그인 필요
				.requestMatchers(HttpMethod.GET, "/broadcasts/new", "/live/new", "/broadcasts/{id}/edit", "/donations/**")
				.authenticated()
				// 결제 서비스의 승인 통지(서버 간 요청). 본문은 믿지 않고 서버가 결제 서비스에 다시 조회해 확인한다.
				.requestMatchers(HttpMethod.POST, PAYMENT_WEBHOOK).permitAll()
				// 그 밖의 조회(목록·상세·댓글 목록·영상·정적 파일·라이브 시청 연결)는 공개. 없는 경로는 404로 응답한다.
				.requestMatchers(HttpMethod.GET, "/**").permitAll()
				.requestMatchers(HttpMethod.POST, "/login", "/signup").permitAll()
				// 상태를 바꾸는 요청(등록·수정·삭제·종료·댓글·로그아웃)은 모두 로그인 필요
				.anyRequest().authenticated())
			.formLogin(form -> form
				.loginPage("/login")
				.defaultSuccessUrl("/broadcasts")
				.permitAll())
			.logout(logout -> logout
				.logoutSuccessUrl("/login?logout")
				.invalidateHttpSession(true)
				.deleteCookies("JSESSIONID"))
			// 결제 서비스가 보내는 승인 통지만 CSRF 검사에서 뺀다(브라우저 세션을 쓰지 않는 서버 간 요청). 나머지는 그대로 검사한다.
			.csrf(csrf -> csrf.ignoringRequestMatchers(PAYMENT_WEBHOOK))
			.sessionManagement(session -> session
				.sessionFixation(fixation -> fixation.changeSessionId()))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(SecurityConfig::requireLogin)
				.accessDeniedHandler(SecurityConfig::denyAccess));
		return http.build();
	}

	/**
	 * 로그인하지 않은 요청. XHR은 401 JSON, 그 밖의 요청은 로그인 화면으로 보낸다(로그인 뒤 원래 화면으로 돌아온다).
	 */
	private static void requireLogin(HttpServletRequest request, HttpServletResponse response,
		AuthenticationException exception) throws IOException, ServletException {
		if (XHR_REQUEST.matches(request)) {
			writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, "로그인이 필요합니다. 로그인한 뒤 다시 시도해 주세요.");
			return;
		}
		LOGIN_PAGE.commence(request, response, exception);
	}

	/**
	 * CSRF 토큰이 없거나 틀린 요청. XHR은 403 JSON, 그 밖의 요청은 403 오류 화면이다.
	 * 소유자·작성자가 아닌 요청은 서비스가 던진 예외를 {@code GlobalExceptionHandler}가 403 화면으로 바꾼다.
	 */
	private static void denyAccess(HttpServletRequest request, HttpServletResponse response,
		AccessDeniedException exception) throws IOException, ServletException {
		if (XHR_REQUEST.matches(request)) {
			writeJson(response, HttpServletResponse.SC_FORBIDDEN, "요청이 거부되었습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.");
			return;
		}
		ACCESS_DENIED_PAGE.handle(request, response, exception);
	}

	/**
	 * 개발용 H2 Console(dev 프로파일에서만 켜짐). 콘솔 자체 로그인을 쓰고 화면을 frame으로 그리므로,
	 * 이 경로에 한해서만 같은 출처 frame을 허용하고 CSRF 검사에서 뺀다. 다른 경로의 CSRF 보호는 그대로다.
	 */
	@Bean
	@Order(1)
	@ConditionalOnProperty(name = "spring.h2.console.enabled", havingValue = "true")
	public SecurityFilterChain h2ConsoleSecurityFilterChain(HttpSecurity http) throws Exception {
		http
			.securityMatcher("/h2-console/**")
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.csrf(csrf -> csrf.ignoringRequestMatchers("/h2-console/**"))
			.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
		return http.build();
	}

	private static void writeJson(HttpServletResponse response, int status, String message) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"message\":\"" + message + "\"}");
	}

}
