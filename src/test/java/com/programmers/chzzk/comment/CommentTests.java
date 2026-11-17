package com.programmers.chzzk.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

import java.time.LocalDateTime;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.programmers.chzzk.TestMembers;
import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.broadcast.BroadcastRepository;
import com.programmers.chzzk.member.Member;
import com.programmers.chzzk.member.MemberRepository;

/**
 * 방송별 댓글 작성·조회·수정·삭제. 비로그인 사용자, 댓글 작성자, 다른 회원으로 나눠 서버의 허용·거절을 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CommentTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private CommentRepository commentRepository;

	@Autowired
	private BroadcastRepository broadcastRepository;

	@Autowired
	private MemberRepository memberRepository;

	private Member author;
	private Member other;
	private long broadcastId;

	@BeforeEach
	void setUp() {
		commentRepository.deleteAll();
		broadcastRepository.deleteAll();
		author = TestMembers.save(memberRepository, "comment_author");
		other = TestMembers.save(memberRepository, "comment_other");
		broadcastId = broadcastRepository.save(
			new Broadcast("댓글 방송", "3f2c8a1e-5b7d-4c9a-8e21-0d6f4b9a7c13.mp4", author)).getId();
	}

	@AfterEach
	void cleanUp() {
		commentRepository.deleteAll();
		broadcastRepository.deleteAll();
	}

	@Test
	void anonymousUserCannotWriteCommentButCanReadComments() throws Exception {
		writeAs(author, "첫 댓글");

		mockMvc.perform(post("/broadcasts/" + broadcastId + "/comments").param("content", "익명 댓글").with(csrf()))
			.andExpect(redirectedUrl("/login"));

		assertThat(commentRepository.count()).isEqualTo(1);
		mockMvc.perform(get("/broadcasts/" + broadcastId))
			.andExpect(status().isOk())
			.andExpect(xpath("//ul[@class='comment-list']/li").nodeCount(1))
			.andExpect(xpath("//ul[@class='comment-list']/li/strong[@class='comment-author']").string("comment_author"))
			.andExpect(xpath("//ul[@class='comment-list']/li/p[@class='comment-content']").string("첫 댓글"))
			.andExpect(xpath("//form[@class='comment-form']").doesNotExist())
			.andExpect(xpath("//p[@class='comment-login']/a[@href='/login']").exists());
	}

	@Test
	void writingWithoutCsrfTokenIsForbidden() throws Exception {
		mockMvc.perform(post("/broadcasts/" + broadcastId + "/comments").param("content", "토큰 없음").with(as(author)))
			.andExpect(status().isForbidden());

		assertThat(commentRepository.count()).isZero();
	}

	@Test
	void loggedInMemberWritesCommentLinkedToBroadcastAndAuthor() throws Exception {
		mockMvc.perform(post("/broadcasts/" + broadcastId + "/comments").param("content", "  반가워요  ")
				.with(as(other)).with(csrf()))
			.andExpect(redirectedUrl("/broadcasts/" + broadcastId + "#comments"));

		Comment saved = commentRepository.findAll().getFirst();
		assertThat(saved.getContent()).isEqualTo("반가워요");
		assertThat(saved.isWrittenBy(other.getId())).isTrue();
		assertThat(commentRepository.findByBroadcastIdOrderByCreatedAtAscIdAsc(broadcastId)).hasSize(1);
	}

	@Test
	void commentsAreListedPerBroadcastInWrittenOrder() throws Exception {
		long anotherBroadcastId = broadcastRepository.save(
			Broadcast.startLive("다른 방송", author, LocalDateTime.now())).getId();
		writeAs(author, "첫째");
		writeAs(other, "둘째");
		mockMvc.perform(post("/broadcasts/" + anotherBroadcastId + "/comments").param("content", "다른 방송 댓글")
			.with(as(other)).with(csrf()));

		mockMvc.perform(get("/broadcasts/" + broadcastId))
			.andExpect(xpath("//ul[@class='comment-list']/li").nodeCount(2))
			.andExpect(xpath("//ul[@class='comment-list']/li[1]/p[@class='comment-content']").string("첫째"))
			.andExpect(xpath("//ul[@class='comment-list']/li[2]/p[@class='comment-content']").string("둘째"));
		mockMvc.perform(get("/broadcasts/" + anotherBroadcastId))
			.andExpect(status().isOk())
			.andExpect(xpath("//ul[@class='comment-list']/li").nodeCount(1));
	}

	@Test
	void blankOrTooLongCommentIsRejectedWithMessage() throws Exception {
		mockMvc.perform(post("/broadcasts/" + broadcastId + "/comments").param("content", "   ")
				.with(as(author)).with(csrf()))
			.andExpect(redirectedUrl("/broadcasts/" + broadcastId + "#comments"))
			.andExpect(flash().attribute(CommentController.COMMENT_ERROR, CommentService.CONTENT_REQUIRED));
		mockMvc.perform(post("/broadcasts/" + broadcastId + "/comments").param("content", "가".repeat(501))
				.with(as(author)).with(csrf()))
			.andExpect(flash().attribute(CommentController.COMMENT_ERROR, CommentService.CONTENT_TOO_LONG));
		mockMvc.perform(post("/broadcasts/" + broadcastId + "/comments").param("content", "가".repeat(500))
			.with(as(author)).with(csrf()));

		assertThat(commentRepository.findAll()).extracting(Comment::getContent).containsExactly("가".repeat(500));
	}

	@Test
	void commentOnMissingBroadcastIsNotFound() throws Exception {
		mockMvc.perform(post("/broadcasts/999999/comments").param("content", "어디에?").with(as(author)).with(csrf()))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/broadcasts/abc/comments").param("content", "어디에?").with(as(author)).with(csrf()))
			.andExpect(status().isNotFound());

		assertThat(commentRepository.count()).isZero();
	}

	@Test
	void onlyAuthorCanEditComment() throws Exception {
		long commentId = writeAs(author, "원래 댓글");

		mockMvc.perform(post("/comments/" + commentId + "/edit").param("content", "익명 수정").with(csrf()))
			.andExpect(redirectedUrl("/login"));
		mockMvc.perform(post("/comments/" + commentId + "/edit").param("content", "남의 수정").with(as(other)).with(csrf()))
			.andExpect(status().isForbidden());
		assertThat(contentOf(commentId)).isEqualTo("원래 댓글");

		mockMvc.perform(post("/comments/" + commentId + "/edit").param("content", "고친 댓글").with(as(author)).with(csrf()))
			.andExpect(redirectedUrl("/broadcasts/" + broadcastId + "#comments"));
		assertThat(contentOf(commentId)).isEqualTo("고친 댓글");
		mockMvc.perform(get("/broadcasts/" + broadcastId))
			.andExpect(xpath("//ul[@class='comment-list']/li/span[@class='comment-edited']").exists());
	}

	@Test
	void invalidEditKeepsOriginalCommentAndShowsMessage() throws Exception {
		long commentId = writeAs(author, "원래 댓글");

		mockMvc.perform(post("/comments/" + commentId + "/edit").param("content", " ").with(as(author)).with(csrf()))
			.andExpect(redirectedUrl("/broadcasts/" + broadcastId + "#comments"))
			.andExpect(flash().attribute(CommentController.COMMENT_ERROR, CommentService.CONTENT_REQUIRED));

		assertThat(contentOf(commentId)).isEqualTo("원래 댓글");
	}

	@Test
	void onlyAuthorCanDeleteComment() throws Exception {
		long commentId = writeAs(author, "지울 댓글");

		mockMvc.perform(post("/comments/" + commentId + "/delete").with(csrf()))
			.andExpect(redirectedUrl("/login"));
		mockMvc.perform(post("/comments/" + commentId + "/delete").with(as(other)).with(csrf()))
			.andExpect(status().isForbidden());
		mockMvc.perform(post("/comments/" + commentId + "/delete").with(as(author)))
			.andExpect(status().isForbidden());
		assertThat(commentRepository.existsById(commentId)).isTrue();

		mockMvc.perform(post("/comments/" + commentId + "/delete").with(as(author)).with(csrf()))
			.andExpect(redirectedUrl("/broadcasts/" + broadcastId + "#comments"));
		assertThat(commentRepository.existsById(commentId)).isFalse();
	}

	@Test
	void editingOrDeletingMissingCommentIsNotFound() throws Exception {
		mockMvc.perform(post("/comments/999999/edit").param("content", "없음").with(as(author)).with(csrf()))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/comments/999999/delete").with(as(author)).with(csrf()))
			.andExpect(status().isNotFound());
		mockMvc.perform(post("/comments/0/delete").with(as(author)).with(csrf()))
			.andExpect(status().isNotFound());
	}

	@Test
	void editAndDeleteButtonsAppearOnlyForAuthor() throws Exception {
		long commentId = writeAs(author, "내 댓글");
		String editForm = "//form[@action='/comments/" + commentId + "/edit']";

		mockMvc.perform(get("/broadcasts/" + broadcastId).with(as(other))).andExpect(xpath(editForm).doesNotExist());
		mockMvc.perform(get("/broadcasts/" + broadcastId).with(as(author)))
			.andExpect(xpath(editForm + "//input[@name='_csrf']").exists())
			.andExpect(xpath("//form[@action='/comments/" + commentId + "/delete']").exists());
	}

	@Test
	void commentContentIsEscapedWhenRendered() throws Exception {
		writeAs(author, "<script>alert('x')</script>");

		mockMvc.perform(get("/broadcasts/" + broadcastId))
			.andExpect(content().string(Matchers.containsString("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;")))
			.andExpect(content().string(Matchers.not(Matchers.containsString("<script>alert('x')</script>"))));
	}

	private long writeAs(Member member, String content) throws Exception {
		mockMvc.perform(post("/broadcasts/" + broadcastId + "/comments").param("content", content)
				.with(as(member)).with(csrf()))
			.andExpect(status().is3xxRedirection());
		return commentRepository.findAll().stream()
			.filter(comment -> comment.getContent().equals(content.strip()))
			.findFirst().orElseThrow().getId();
	}

	private String contentOf(long commentId) {
		return commentRepository.findById(commentId).orElseThrow().getContent();
	}

	private static RequestPostProcessor as(Member member) {
		return user(TestMembers.loginMember(member));
	}

}
