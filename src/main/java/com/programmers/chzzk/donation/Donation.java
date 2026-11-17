package com.programmers.chzzk.donation;

import java.time.LocalDateTime;

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

import com.programmers.chzzk.broadcast.Broadcast;
import com.programmers.chzzk.member.Member;

/**
 * 후원 주문 하나(결제 한 건). 주문번호와 결제 키는 각각 DB에서 유일해, 같은 결제가 두 번 후원으로 적립되지 않는다.
 * 금액과 수신 대상(후원 시점의 방송 소유자)은 주문을 만들 때 서버가 정하고, 승인 결과와 비교할 때 이 값을 기준으로 한다.
 * 상태 변경은 {@link DonationRepository}의 조건부 UPDATE로만 한다(동시에 들어온 요청 중 하나만 바뀜).
 */
@Entity
public class Donation {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true, length = 64)
	private String orderId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "broadcast_id", nullable = false)
	private Broadcast broadcast;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "donor_id", nullable = false)
	private Member donor;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "recipient_id", nullable = false)
	private Member recipient;

	@Column(nullable = false)
	private long amount;

	@Column(length = 100)
	private String message;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private DonationStatus status;

	@Column(unique = true, length = 200)
	private String paymentKey;

	@Column(length = 60)
	private String failureCode;

	@Column(nullable = false)
	private LocalDateTime createdAt;

	private LocalDateTime approvedAt;

	protected Donation() {
	}

	public Donation(String orderId, Broadcast broadcast, Member donor, Member recipient, long amount, String message) {
		this.orderId = orderId;
		this.broadcast = broadcast;
		this.donor = donor;
		this.recipient = recipient;
		this.amount = amount;
		this.message = message;
		this.status = DonationStatus.READY;
	}

	@PrePersist
	void assignCreatedAt() {
		if (createdAt == null) {
			createdAt = LocalDateTime.now();
		}
	}

	public boolean isOrderedBy(Long memberId) {
		return memberId != null && memberId.equals(donor.getId());
	}

	public Long getId() {
		return id;
	}

	public String getOrderId() {
		return orderId;
	}

	public Broadcast getBroadcast() {
		return broadcast;
	}

	public Member getDonor() {
		return donor;
	}

	public Member getRecipient() {
		return recipient;
	}

	public long getAmount() {
		return amount;
	}

	public String getMessage() {
		return message;
	}

	public DonationStatus getStatus() {
		return status;
	}

	public String getPaymentKey() {
		return paymentKey;
	}

	public String getFailureCode() {
		return failureCode;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public LocalDateTime getApprovedAt() {
		return approvedAt;
	}

}
