package com.programmers.chzzk.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.programmers.chzzk.TestMembers;

/**
 * 회원가입·로그인·로그아웃과 세션 처리(BCrypt 저장, CSRF, 로그아웃 시 세션 폐기).
 * 세션 고정 방어(로그인 시 세션 ID 변경)는 실제 Tomcat 세션으로 {@link SessionServerTests}에서 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberAuthenticationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MemberRepository memberRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void signUpStoresBcryptHashAndRedirectsToLogin() throws Exception {
		mockMvc.perform(post("/signup").with(csrf())
				.param("username", "new_member")
				.param("password", "password-1234")
				.param("passwordConfirm", "password-1234"))
			.andExpect(status().is3xxRedirection())
			.andExpect(redirectedUrl("/login?signedUp"));

		Member saved = memberRepository.findByUsername("new_member").orElseThrow();
		assertThat(saved.getPasswordHash()).startsWith("$2").isNotEqualTo("password-1234");
		assertThat(passwordEncoder.matches("password-1234", saved.getPasswordHash())).isTrue();
	}

	@Test
	void signUpRejectsInvalidInputAndDuplicateUsername() throws Exception {
		TestMembers.save(memberRepository, "taken_name");

		assertSignUpRejected("ab", "password-1234", "password-1234", MemberService.INVALID_USERNAME);
		assertSignUpRejected("Upper_Case", "password-1234", "password-1234", MemberService.INVALID_USERNAME);
		assertSignUpRejected("valid_name", "short", "short", MemberService.INVALID_PASSWORD);
		assertSignUpRejected("valid_name", "password-1234", "password-9999", MemberService.PASSWORD_MISMATCH);
		assertSignUpRejected("taken_name", "password-1234", "password-1234", MemberService.DUPLICATE_USERNAME);
		assertThat(memberRepository.existsByUsername("valid_name")).isFalse();
	}

	@Test
	void signUpWithoutCsrfTokenIsForbidden() throws Exception {
		mockMvc.perform(post("/signup")
				.param("username", "no_csrf_user")
				.param("password", "password-1234")
				.param("passwordConfirm", "password-1234"))
			.andExpect(status().isForbidden());

		assertThat(memberRepository.existsByUsername("no_csrf_user")).isFalse();
	}

	@Test
	void loginWithCorrectPasswordAuthenticates() throws Exception {
		TestMembers.saveWithPassword(memberRepository, passwordEncoder, "login_ok");

		mockMvc.perform(formLogin("/login").user("login_ok").password(TestMembers.PASSWORD))
			.andExpect(authenticated().withUsername("login_ok"))
			.andExpect(redirectedUrl("/broadcasts"));
	}

	@Test
	void loginWithWrongPasswordOrUnknownUserFails() throws Exception {
		TestMembers.saveWithPassword(memberRepository, passwordEncoder, "login_wrong");

		mockMvc.perform(formLogin("/login").user("login_wrong").password("not-the-password"))
			.andExpect(unauthenticated())
			.andExpect(redirectedUrl("/login?error"));
		mockMvc.perform(formLogin("/login").user("nobody_here").password(TestMembers.PASSWORD))
			.andExpect(unauthenticated())
			.andExpect(redirectedUrl("/login?error"));
	}

	@Test
	void loginPageShowsErrorMessage() throws Exception {
		mockMvc.perform(get("/login").param("error", ""))
			.andExpect(status().isOk())
			.andExpect(xpath("//p[@class='error-message']").string("아이디 또는 비밀번호가 올바르지 않습니다."))
			.andExpect(xpath("//form[@action='/login'][@method='post']//input[@name='_csrf']").exists());
	}

	@Test
	void logoutInvalidatesSessionAndRequiresLoginAgain() throws Exception {
		TestMembers.saveWithPassword(memberRepository, passwordEncoder, "logout_member");
		MockHttpSession session = (MockHttpSession)mockMvc.perform(
				formLogin("/login").user("logout_member").password(TestMembers.PASSWORD))
			.andExpect(authenticated())
			.andReturn().getRequest().getSession();
		mockMvc.perform(get("/broadcasts/new").session(session))
			.andExpect(status().isOk());

		mockMvc.perform(post("/logout").with(csrf()).session(session))
			.andExpect(redirectedUrl("/login?logout"))
			.andExpect(unauthenticated());

		assertThat(session.isInvalid()).isTrue();
		mockMvc.perform(get("/broadcasts/new"))
			.andExpect(redirectedUrl("/login"));
	}

	@Test
	void logoutWithoutCsrfTokenDoesNotLogOut() throws Exception {
		TestMembers.saveWithPassword(memberRepository, passwordEncoder, "logout_no_csrf");
		MockHttpSession session = (MockHttpSession)mockMvc.perform(
				formLogin("/login").user("logout_no_csrf").password(TestMembers.PASSWORD))
			.andReturn().getRequest().getSession();

		mockMvc.perform(post("/logout").session(session))
			.andExpect(status().isForbidden());

		assertThat(session.isInvalid()).isFalse();
		mockMvc.perform(get("/broadcasts/new").session(session))
			.andExpect(status().isOk());
	}

	@Test
	void headerShowsLoginStateAndLogoutForm() throws Exception {
		mockMvc.perform(get("/broadcasts"))
			.andExpect(xpath("//header//a[@href='/login']").exists())
			.andExpect(xpath("//header//a[@href='/signup']").exists());

		TestMembers.saveWithPassword(memberRepository, passwordEncoder, "header_member");
		MockHttpSession session = (MockHttpSession)mockMvc.perform(
				formLogin("/login").user("header_member").password(TestMembers.PASSWORD))
			.andReturn().getRequest().getSession();
		mockMvc.perform(get("/broadcasts").session(session))
			.andExpect(xpath("//header//*[@class='login-member']").string("header_member님"))
			.andExpect(xpath("//header//form[@action='/logout'][@method='post']//input[@name='_csrf']").exists());
	}

	private void assertSignUpRejected(String username, String password, String passwordConfirm, String message)
		throws Exception {
		mockMvc.perform(post("/signup").with(csrf())
				.param("username", username)
				.param("password", password)
				.param("passwordConfirm", passwordConfirm))
			.andExpect(status().isBadRequest())
			.andExpect(xpath("//p[@class='error-message']").string(message));
	}

}
