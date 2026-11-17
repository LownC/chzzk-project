package com.programmers.chzzk.broadcast;

import java.time.Duration;
import java.time.LocalDateTime;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;

/**
 * 녹화방송과 라이브 방송을 한 테이블에 둔다. 라이브 방송은 LIVE에서 ENDED로만 바뀐다(docs/live-streaming.md 3절).
 * 상태 컬럼은 DB 네이티브 enum이 아닌 문자열로 저장해 값이 늘어도 기존 스키마를 바꾸지 않게 한다.
 */
@Entity
public class Broadcast {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String title;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "broadcast_type", nullable = false, length = 20)
	@ColumnDefault("'RECORDED'")
	private BroadcastType type = BroadcastType.RECORDED;

	/**
	 * 녹화방송에만 있다. 라이브 방송은 영상을 서버에 저장하지 않는다.
	 */
	private String videoFileName;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(length = 20)
	private LiveStatus liveStatus;

	private LocalDateTime startedAt;

	private LocalDateTime endedAt;

	private LocalDateTime lastHeartbeatAt;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	protected Broadcast() {
	}

	public Broadcast(String title, String videoFileName) {
		this.title = title;
		this.videoFileName = videoFileName;
	}

	public static Broadcast startLive(String title, LocalDateTime now) {
		Broadcast broadcast = new Broadcast();
		broadcast.title = title;
		broadcast.type = BroadcastType.LIVE;
		broadcast.liveStatus = LiveStatus.LIVE;
		broadcast.startedAt = now;
		broadcast.lastHeartbeatAt = now;
		broadcast.createdAt = now;
		return broadcast;
	}

	@PrePersist
	void assignCreatedAt() {
		if (createdAt == null) {
			createdAt = LocalDateTime.now();
		}
	}

	/**
	 * 송출자가 아직 연결되어 있다는 신호를 기록한다. 종료된 방송은 다시 살아나지 않는다.
	 */
	public void recordHeartbeat(LocalDateTime now) {
		requireLiveType();
		if (liveStatus != LiveStatus.LIVE) {
			throw new IllegalStateException("종료된 라이브 방송입니다: id=" + id);
		}
		lastHeartbeatAt = now;
	}

	/**
	 * 방송을 종료한다. 이미 종료된 방송이면 아무것도 바꾸지 않고 false를 돌려준다.
	 */
	public boolean endLive(LocalDateTime now) {
		requireLiveType();
		if (liveStatus == LiveStatus.ENDED) {
			return false;
		}
		liveStatus = LiveStatus.ENDED;
		endedAt = now;
		return true;
	}

	public boolean isLiveNow() {
		return type == BroadcastType.LIVE && liveStatus == LiveStatus.LIVE;
	}

	public boolean isHeartbeatExpired(LocalDateTime now, Duration timeout) {
		return isLiveNow() && lastHeartbeatAt.plus(timeout).isBefore(now);
	}

	private void requireLiveType() {
		if (type != BroadcastType.LIVE) {
			throw new IllegalStateException("라이브 방송이 아닙니다: id=" + id);
		}
	}

	public Long getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public BroadcastType getType() {
		return type;
	}

	public String getVideoFileName() {
		return videoFileName;
	}

	public LiveStatus getLiveStatus() {
		return liveStatus;
	}

	public LocalDateTime getStartedAt() {
		return startedAt;
	}

	public LocalDateTime getEndedAt() {
		return endedAt;
	}

	public LocalDateTime getLastHeartbeatAt() {
		return lastHeartbeatAt;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

}
