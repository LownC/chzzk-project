package com.programmers.chzzk.member;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.ModelAndView;

/**
 * 회원가입·로그인 화면. 로그인 처리(POST /login)와 로그아웃(POST /logout)은 Spring Security가 맡는다.
 */
@Controller
public class MemberController {

	private final MemberService memberService;

	public MemberController(MemberService memberService) {
		this.memberService = memberService;
	}

	@GetMapping("/login")
	public String loginForm() {
		return "login";
	}

	@GetMapping("/signup")
	public String signUpForm() {
		return "signup";
	}

	@PostMapping("/signup")
	public ModelAndView signUp(
		@RequestParam(required = false) String username,
		@RequestParam(required = false) String password,
		@RequestParam(required = false) String passwordConfirm
	) {
		try {
			memberService.signUp(username, password, passwordConfirm);
		} catch (SignUpException e) {
			ModelAndView signUpPage = new ModelAndView("signup", HttpStatus.BAD_REQUEST);
			signUpPage.addObject("username", username);
			signUpPage.addObject("errorMessage", e.getMessage());
			return signUpPage;
		}
		return new ModelAndView("redirect:/login?signedUp");
	}

}
