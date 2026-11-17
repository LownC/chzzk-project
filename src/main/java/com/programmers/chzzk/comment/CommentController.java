package com.programmers.chzzk.comment;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.programmers.chzzk.global.exception.NotFoundException;
import com.programmers.chzzk.member.LoginMember;

/**
 * 댓글 작성·수정·삭제. 모든 요청은 로그인이 필요하고(보안 설정), 수정·삭제는 작성자만 할 수 있다(서비스).
 * 처리 뒤에는 방송 상세 화면의 댓글 영역으로 돌아간다. 입력 오류는 상세 화면에 문구로 보여 준다.
 */
@Controller
public class CommentController {

	static final String COMMENT_ERROR = "commentError";

	private final CommentService commentService;

	public CommentController(CommentService commentService) {
		this.commentService = commentService;
	}

	@PostMapping("/broadcasts/{broadcastId}/comments")
	public String write(@PathVariable String broadcastId, @RequestParam(required = false) String content,
		@AuthenticationPrincipal LoginMember loginMember, RedirectAttributes redirectAttributes) {
		long id = parseId(broadcastId, "방송");
		try {
			commentService.write(id, loginMember.getId(), content);
		} catch (CommentValidationException e) {
			redirectAttributes.addFlashAttribute(COMMENT_ERROR, e.getMessage());
		}
		return redirectToComments(id);
	}

	@PostMapping("/comments/{commentId}/edit")
	public String edit(@PathVariable String commentId, @RequestParam(required = false) String content,
		@AuthenticationPrincipal LoginMember loginMember, RedirectAttributes redirectAttributes) {
		long id = parseId(commentId, "댓글");
		try {
			return redirectToComments(commentService.edit(id, loginMember.getId(), content));
		} catch (CommentValidationException e) {
			redirectAttributes.addFlashAttribute(COMMENT_ERROR, e.getMessage());
			return redirectToComments(commentService.broadcastIdOf(id));
		}
	}

	@PostMapping("/comments/{commentId}/delete")
	public String delete(@PathVariable String commentId, @AuthenticationPrincipal LoginMember loginMember) {
		return redirectToComments(commentService.delete(parseId(commentId, "댓글"), loginMember.getId()));
	}

	/**
	 * 숫자가 아님, Long 범위 초과, 0 이하는 모두 대상 없음(404)이다.
	 */
	private static long parseId(String id, String target) {
		try {
			long parsedId = Long.parseLong(id);
			if (parsedId > 0) {
				return parsedId;
			}
		} catch (NumberFormatException e) {
			// 아래에서 대상 없음으로 응답한다.
		}
		throw new NotFoundException(target + " ID 형식 오류: " + id);
	}

	private static String redirectToComments(long broadcastId) {
		return "redirect:/broadcasts/" + broadcastId + "#comments";
	}

}
