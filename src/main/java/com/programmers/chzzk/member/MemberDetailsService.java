package com.programmers.chzzk.member;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 폼 로그인에서 아이디로 회원을 찾는다. 비밀번호 비교는 Spring Security가 BCrypt로 한다.
 */
@Service
public class MemberDetailsService implements UserDetailsService {

	private final MemberRepository memberRepository;

	public MemberDetailsService(MemberRepository memberRepository) {
		this.memberRepository = memberRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String username) {
		return memberRepository.findByUsername(username)
			.map(LoginMember::from)
			.orElseThrow(() -> new UsernameNotFoundException("회원 없음"));
	}

}
