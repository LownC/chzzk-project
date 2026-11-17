package com.programmers.chzzk;

import org.springframework.security.crypto.password.PasswordEncoder;

import com.programmers.chzzk.member.LoginMember;
import com.programmers.chzzk.member.Member;
import com.programmers.chzzk.member.MemberRepository;

/**
 * 테스트용 회원. 같은 아이디가 이미 있으면 그 회원을 쓴다(같은 스프링 컨텍스트를 공유하는 테스트 사이에 회원이 남는다).
 */
public final class TestMembers {

	public static final String PASSWORD = "test-password-1";

	private TestMembers() {
	}

	/**
	 * 실제 로그인이 필요 없는 테스트용. 비밀번호 해시는 쓰이지 않는 값이다.
	 */
	public static Member save(MemberRepository memberRepository, String username) {
		return memberRepository.findByUsername(username)
			.orElseGet(() -> memberRepository.save(new Member(username, "unused-password-hash")));
	}

	/**
	 * 폼 로그인까지 하는 테스트용. 비밀번호는 {@link #PASSWORD}다.
	 */
	public static Member saveWithPassword(MemberRepository memberRepository, PasswordEncoder passwordEncoder,
		String username) {
		return memberRepository.findByUsername(username)
			.orElseGet(() -> memberRepository.save(new Member(username, passwordEncoder.encode(PASSWORD))));
	}

	public static LoginMember loginMember(Member member) {
		return new LoginMember(member.getId(), member.getUsername(), member.getPasswordHash());
	}

}
