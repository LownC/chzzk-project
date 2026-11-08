package com.programmers.chzzk.broadcast;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;

@Entity
public class Broadcast {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false)
	private String videoFileName;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	protected Broadcast() {
	}

	public Broadcast(String title, String videoFileName) {
		this.title = title;
		this.videoFileName = videoFileName;
	}

	@PrePersist
	void assignCreatedAt() {
		createdAt = LocalDateTime.now();
	}

	public Long getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public String getVideoFileName() {
		return videoFileName;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

}
