package com.programmers.chzzk.comment;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.broadcast.BroadcastRepository;
import com.programmers.chzzk.global.exception.NotFoundException;
import com.programmers.chzzk.member.MemberRepository;

/**
 * 방송별 댓글. 작성은 로그인한 회원만(로그인 검사는 보안 설정), 수정·삭제는 작성자만(이 서비스가 회원 ID로 검사) 할 수 있다.
 */
@Service
public class CommentService {

	private static final Logger log = LoggerFactory.getLogger(CommentService.class);

	static final int MAX_CONTENT_LENGTH = 500;
	static final String CONTENT_REQUIRED = "댓글 내용을 입력해 주세요.";
	static final String CONTENT_TOO_LONG = "댓글은 최대 500자까지 쓸 수 있습니다.";

	private final CommentRepository commentRepository;
	private final BroadcastRepository broadcastRepository;
	private final MemberRepository memberRepository;
	private final Clock clock;

	public CommentService(CommentRepository commentRepository, BroadcastRepository broadcastRepository,
		MemberRepository memberRepository, Clock clock) {
		this.commentRepository = commentRepository;
		this.broadcastRepository = broadcastRepository;
		this.memberRepository = memberRepository;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<Comment> findComments(long broadcastId) {
		return commentRepository.findByBroadcastIdOrderByCreatedAtAscIdAsc(broadcastId);
	}

	@Transactional
	public Long write(long broadcastId, long authorId, String content) {
		Broadcast broadcast = broadcastRepository.findById(broadcastId)
			.orElseThrow(() -> new NotFoundException("방송 없음: id=" + broadcastId));
		String validContent = validate(content);
		Comment comment = commentRepository.save(
			new Comment(broadcast, memberRepository.getReferenceById(authorId), validContent));
		log.info("댓글 작성: commentId={}, broadcastId={}, authorId={}", comment.getId(), broadcastId, authorId);
		return comment.getId();
	}

	/**
	 * 작성자만 수정할 수 있다. 수정한 댓글이 달린 방송 ID를 돌려준다.
	 */
	@Transactional
	public long edit(long commentId, long memberId, String content) {
		Comment comment = findOwnComment(commentId, memberId);
		comment.edit(validate(content), LocalDateTime.now(clock));
		log.info("댓글 수정: commentId={}, memberId={}", commentId, memberId);
		return comment.getBroadcast().getId();
	}

	/**
	 * 작성자만 삭제할 수 있다. 삭제한 댓글이 달린 방송 ID를 돌려준다.
	 */
	@Transactional
	public long delete(long commentId, long memberId) {
		Comment comment = findOwnComment(commentId, memberId);
		long broadcastId = comment.getBroadcast().getId();
		commentRepository.delete(comment);
		log.info("댓글 삭제: commentId={}, memberId={}", commentId, memberId);
		return broadcastId;
	}

	@Transactional(readOnly = true)
	public long broadcastIdOf(long commentId) {
		return commentRepository.findById(commentId)
			.map(comment -> comment.getBroadcast().getId())
			.orElseThrow(() -> new NotFoundException("댓글 없음: id=" + commentId));
	}

	private Comment findOwnComment(long commentId, long memberId) {
		Comment comment = commentRepository.findById(commentId)
			.orElseThrow(() -> new NotFoundException("댓글 없음: id=" + commentId));
		if (!comment.isWrittenBy(memberId)) {
			throw new AccessDeniedException("댓글 작성자가 아님: commentId=" + commentId + ", memberId=" + memberId);
		}
		return comment;
	}

	private static String validate(String content) {
		if (content == null || content.isBlank()) {
			throw new CommentValidationException(CONTENT_REQUIRED);
		}
		String stripped = content.strip();
		if (stripped.length() > MAX_CONTENT_LENGTH) {
			throw new CommentValidationException(CONTENT_TOO_LONG);
		}
		return stripped;
	}

}
