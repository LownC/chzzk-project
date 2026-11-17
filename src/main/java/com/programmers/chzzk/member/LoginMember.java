package com.programmers.chzzk.member;

import java.util.Collection;
import java.util.List;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 로그인한 회원. 세션의 인증 정보에 들어가며, 권한 검사는 회원 ID로 한다.
 * 로그인이 끝나면 Spring Security가 {@link #eraseCredentials()}를 불러 비밀번호 해시를 세션에 남기지 않는다.
 */
public class LoginMember implements UserDetails, CredentialsContainer {

	private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_USER"));

	private final Long id;
	private final String username;
	private String passwordHash;

	public LoginMember(Long id, String username, String passwordHash) {
		this.id = id;
		this.username = username;
		this.passwordHash = passwordHash;
	}

	public static LoginMember from(Member member) {
		return new LoginMember(member.getId(), member.getUsername(), member.getPasswordHash());
	}

	public Long getId() {
		return id;
	}

	@Override
	public String getUsername() {
		return username;
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return AUTHORITIES;
	}

	@Override
	public void eraseCredentials() {
		passwordHash = null;
	}

}
