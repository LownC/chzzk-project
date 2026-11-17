package com.programmers.chzzk.donation;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 후원 주문. 상태 변경은 모두 "현재 상태가 기대한 값일 때만" 바꾸는 조건부 UPDATE다. 바뀐 행 수가 0이면
 * 다른 요청이 먼저 처리한 것이므로, 같은 결제가 두 번 확정되거나 확정된 후원이 되돌아가지 않는다.
 */
public interface DonationRepository extends JpaRepository<Donation, Long> {

	@EntityGraph(attributePaths = {"broadcast", "donor", "recipient"})
	Optional<Donation> findByOrderId(String orderId);

	/**
	 * 방송의 확정된 후원을 최근 승인 순으로 후원자와 함께 읽는다.
	 */
	@EntityGraph(attributePaths = "donor")
	List<Donation> findByBroadcastIdAndStatusOrderByApprovedAtDescIdDesc(Long broadcastId, DonationStatus status);

	@Query("select coalesce(sum(d.amount), 0) from Donation d where d.broadcast.id = :broadcastId and d.status = :status")
	long sumAmount(@Param("broadcastId") Long broadcastId, @Param("status") DonationStatus status);

	boolean existsByBroadcastIdAndStatusIn(Long broadcastId, Collection<DonationStatus> statuses);

	/**
	 * 승인 요청을 맡는다. READY인 주문만 IN_PROGRESS로 바꾸고 결제 키를 기록한다. 결제 키가 이미 다른 주문에 쓰였으면
	 * 유일 제약 위반 예외가 난다.
	 */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Donation d set d.status = com.programmers.chzzk.donation.DonationStatus.IN_PROGRESS,"
		+ " d.paymentKey = :paymentKey"
		+ " where d.orderId = :orderId and d.status = com.programmers.chzzk.donation.DonationStatus.READY")
	int claim(@Param("orderId") String orderId, @Param("paymentKey") String paymentKey);

	/**
	 * 승인 요청을 맡은 주문만 확정한다.
	 */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Donation d set d.status = com.programmers.chzzk.donation.DonationStatus.DONE, d.approvedAt = :approvedAt"
		+ " where d.orderId = :orderId and d.paymentKey = :paymentKey"
		+ " and d.status = com.programmers.chzzk.donation.DonationStatus.IN_PROGRESS")
	int complete(@Param("orderId") String orderId, @Param("paymentKey") String paymentKey,
		@Param("approvedAt") LocalDateTime approvedAt);

	/**
	 * 아직 확정되지 않은 주문(READY·IN_PROGRESS)을 실패나 취소로 끝낸다. 확정된 후원은 바뀌지 않는다.
	 */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Donation d set d.status = :status, d.failureCode = :failureCode"
		+ " where d.orderId = :orderId and d.status in :from")
	int finish(@Param("orderId") String orderId, @Param("status") DonationStatus status,
		@Param("failureCode") String failureCode, @Param("from") Collection<DonationStatus> from);

	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Donation d where d.broadcast.id = :broadcastId")
	int deleteByBroadcastId(@Param("broadcastId") Long broadcastId);

}
