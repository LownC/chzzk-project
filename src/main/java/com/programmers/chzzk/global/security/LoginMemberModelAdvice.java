package com.programmers.chzzk.global.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.programmers.chzzk.member.LoginMember;

/**
 * 모든 화면에 로그인한 회원(비로그인이면 null)을 넣어 헤더의 로그인 상태와 소유자·작성자 버튼 표시에 쓴다.
 * 버튼 표시는 편의일 뿐이고, 실제 권한 검사는 서버의 각 서비스가 한다.
 */
@ControllerAdvice
public class LoginMemberModelAdvice {

	@ModelAttribute("loginMember")
	public LoginMember loginMember(@AuthenticationPrincipal LoginMember loginMember) {
		return loginMember;
	}

}
