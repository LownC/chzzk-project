package com.programmers.chzzk.broadcast;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;

import com.programmers.chzzk.member.Member;

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

	/**
	 * 방송을 등록·시작한 회원. ③ 이전에 만든 방송은 소유자가 없어(null) 누구도 수정·삭제·종료할 수 없다.
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "owner_id")
	private Member owner;

	protected Broadcast() {
	}

	/**
	 * 소유자 없는 녹화방송. ③ 이전 데이터와 같은 상태를 만드는 테스트용이다. 새 방송은 소유자를 받는 생성자로 만든다.
	 */
	public Broadcast(String title, String videoFileName) {
		this(title, videoFileName, null);
	}

	public Broadcast(String title, String videoFileName, Member owner) {
		this.title = title;
		this.videoFileName = videoFileName;
		this.owner = owner;
	}

	public static Broadcast startLive(String title, Member owner, LocalDateTime now) {
		Broadcast broadcast = new Broadcast();
		broadcast.owner = Objects.requireNonNull(owner, "라이브 방송에는 소유자가 있어야 합니다.");
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

	/**
	 * 회원이 이 방송의 소유자인지. 소유자가 없는 방송(③ 이전 데이터)은 누구의 것도 아니다.
	 * 지연 로딩 프록시라도 ID 비교는 추가 조회 없이 끝난다.
	 */
	public boolean isOwnedBy(Long memberId) {
		return owner != null && memberId != null && memberId.equals(owner.getId());
	}

	public void changeTitle(String title) {
		this.title = title;
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

	public Member getOwner() {
		return owner;
	}

}
