package com.programmers.chzzk.comment;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;

import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.member.Member;

/**
 * 방송 하나에 달린 댓글. 작성자는 로그인한 회원이고, 작성자만 수정·삭제할 수 있다.
 */
@Entity
public class Comment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "broadcast_id", nullable = false)
	private Broadcast broadcast;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "author_id", nullable = false)
	private Member author;

	@Column(nullable = false, length = 500)
	private String content;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	private LocalDateTime updatedAt;

	protected Comment() {
	}

	public Comment(Broadcast broadcast, Member author, String content) {
		this.broadcast = broadcast;
		this.author = author;
		this.content = content;
	}

	@PrePersist
	void assignCreatedAt() {
		if (createdAt == null) {
			createdAt = LocalDateTime.now();
		}
	}

	/**
	 * 지연 로딩 프록시라도 ID 비교는 추가 조회 없이 끝난다.
	 */
	public boolean isWrittenBy(Long memberId) {
		return memberId != null && memberId.equals(author.getId());
	}

	public void edit(String content, LocalDateTime now) {
		this.content = content;
		this.updatedAt = now;
	}

	public Long getId() {
		return id;
	}

	public Broadcast getBroadcast() {
		return broadcast;
	}

	public Member getAuthor() {
		return author;
	}

	public String getContent() {
		return content;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

}
