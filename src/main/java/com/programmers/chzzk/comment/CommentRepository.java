package com.programmers.chzzk.comment;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CommentRepository extends JpaRepository<Comment, Long> {

	/**
	 * 방송의 댓글을 작성 순서대로 작성자와 함께 읽는다(작성자 이름 표시에 추가 조회가 없다).
	 */
	@EntityGraph(attributePaths = "author")
	List<Comment> findByBroadcastIdOrderByCreatedAtAscIdAsc(Long broadcastId);

	/**
	 * 방송을 지우기 전에 그 방송의 댓글을 한 번에 지운다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Comment c where c.broadcast.id = :broadcastId")
	int deleteByBroadcastId(@Param("broadcastId") Long broadcastId);

}
