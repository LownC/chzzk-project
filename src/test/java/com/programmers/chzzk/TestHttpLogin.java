package com.programmers.chzzk;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;

/**
 * 실제 서버(RANDOM_PORT)에 브라우저처럼 폼 로그인하는 테스트 도우미. 로그인 화면의 CSRF 토큰을 함께 보낸다.
 */
public final class TestHttpLogin {

	private static final Pattern CSRF_INPUT = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
	private static final Pattern CSRF_META = Pattern.compile("<meta name=\"_csrf\" content=\"([^\"]+)\"");

	private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

	private TestHttpLogin() {
	}

	/**
	 * 로그인하고 로그인 뒤 세션 쿠키("JSESSIONID=...")를 돌려준다.
	 */
	public static String login(int port, String username, String password) throws Exception {
		HttpResponse<String> loginPage = HTTP.send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login")).GET().build(),
			HttpResponse.BodyHandlers.ofString());
		Matcher csrf = CSRF_INPUT.matcher(loginPage.body());
		assertThat(csrf.find()).as("로그인 화면에 CSRF 토큰이 있어야 한다").isTrue();
		HttpResponse<String> loggedIn = postLogin(port, sessionCookieOf(loginPage), username, password, csrf.group(1));
		assertThat(loggedIn.statusCode()).isEqualTo(302);
		assertThat(loggedIn.headers().firstValue(HttpHeaders.LOCATION)).hasValueSatisfying(
			location -> assertThat(location).doesNotContain("error"));
		return sessionCookieOf(loggedIn);
	}

	/**
	 * 로그인 전 세션 쿠키. 세션 고정 방어 확인용으로, 로그인 화면을 열 때 받은 쿠키를 돌려준다.
	 */
	public static String[] loginKeepingPreLoginCookie(int port, String username, String password) throws Exception {
		HttpResponse<String> loginPage = HTTP.send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login")).GET().build(),
			HttpResponse.BodyHandlers.ofString());
		Matcher csrf = CSRF_INPUT.matcher(loginPage.body());
		assertThat(csrf.find()).isTrue();
		String preLoginCookie = sessionCookieOf(loginPage);
		HttpResponse<String> loggedIn = postLogin(port, preLoginCookie, username, password, csrf.group(1));
		assertThat(loggedIn.statusCode()).isEqualTo(302);
		return new String[] {preLoginCookie, sessionCookieOf(loggedIn)};
	}

	/**
	 * 로그인한 세션으로 방송 등록 화면을 열어 XHR 업로드가 헤더로 보낼 CSRF 토큰을 꺼낸다.
	 */
	public static String csrfTokenFromRegistrationPage(int port, String sessionCookie) throws Exception {
		HttpResponse<String> page = get(port, "/broadcasts/new", sessionCookie);
		assertThat(page.statusCode()).isEqualTo(200);
		Matcher token = CSRF_META.matcher(page.body());
		assertThat(token.find()).as("등록 화면에 CSRF meta가 있어야 한다").isTrue();
		return token.group(1);
	}

	public static HttpResponse<String> get(int port, String path, String sessionCookie) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
		if (sessionCookie != null) {
			request.header(HttpHeaders.COOKIE, sessionCookie);
		}
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static HttpResponse<String> postLogin(int port, String cookie, String username, String password,
		String csrfToken) throws Exception {
		String form = "username=" + encode(username) + "&password=" + encode(password) + "&_csrf=" + encode(csrfToken);
		return HTTP.send(
			HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
				.header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
				.header(HttpHeaders.COOKIE, cookie)
				.POST(HttpRequest.BodyPublishers.ofString(form))
				.build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private static String sessionCookieOf(HttpResponse<String> response) {
		return response.headers().allValues(HttpHeaders.SET_COOKIE).stream()
			.filter(cookie -> cookie.startsWith("JSESSIONID="))
			.map(cookie -> cookie.substring(0, cookie.indexOf(';')))
			.findFirst()
			.orElseThrow(() -> new AssertionError("세션 쿠키가 없음"));
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

}
