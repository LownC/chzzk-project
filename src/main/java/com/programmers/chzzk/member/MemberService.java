package com.programmers.chzzk.member;

import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class MemberService {

	private static final Logger log = LoggerFactory.getLogger(MemberService.class);

	private static final Pattern USERNAME = Pattern.compile("[a-z0-9_]{4,20}");
	private static final int MIN_PASSWORD_LENGTH = 8;
	private static final int MAX_PASSWORD_LENGTH = 64;

	static final String INVALID_USERNAME = "아이디는 영문 소문자·숫자·밑줄(_) 4~20자로 입력해 주세요.";
	static final String INVALID_PASSWORD = "비밀번호는 8~64자로 입력해 주세요.";
	static final String PASSWORD_MISMATCH = "비밀번호 확인이 일치하지 않습니다.";
	static final String DUPLICATE_USERNAME = "이미 사용 중인 아이디입니다.";

	private final MemberRepository memberRepository;
	private final PasswordEncoder passwordEncoder;

	public MemberService(MemberRepository memberRepository, PasswordEncoder passwordEncoder) {
		this.memberRepository = memberRepository;
		this.passwordEncoder = passwordEncoder;
	}

	/**
	 * 회원을 만든다. 비밀번호는 BCrypt 해시로만 저장하고 로그에 남기지 않는다.
	 * 같은 아이디로 동시에 가입하면 DB의 유일 제약이 막고, 이 경우도 중복 아이디로 응답한다.
	 */
	public Long signUp(String username, String password, String passwordConfirm) {
		if (username == null || !USERNAME.matcher(username).matches()) {
			throw new SignUpException(INVALID_USERNAME);
		}
		if (password == null || password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
			throw new SignUpException(INVALID_PASSWORD);
		}
		if (!password.equals(passwordConfirm)) {
			throw new SignUpException(PASSWORD_MISMATCH);
		}
		if (memberRepository.existsByUsername(username)) {
			throw new SignUpException(DUPLICATE_USERNAME);
		}
		Member member;
		try {
			member = memberRepository.save(new Member(username, passwordEncoder.encode(password)));
		} catch (DataIntegrityViolationException e) {
			throw new SignUpException(DUPLICATE_USERNAME);
		}
		log.info("회원가입: memberId={}, username={}", member.getId(), username);
		return member.getId();
	}

}
