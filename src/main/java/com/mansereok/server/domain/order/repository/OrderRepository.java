package com.mansereok.server.domain.order.repository;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

	@Query(
		value = "SELECT * FROM orders WHERE merchant_uid = :merchantUid",
		nativeQuery = true
	)
	Optional<Order> findByMerchantUid(
		@Param("merchantUid") String merchantUid
	);

	Optional<Order> findByPaymentPkId(Long paymentPkId);

	@Modifying(clearAutomatically = true)
	@Query(
		value = "UPDATE orders SET user_id = NULL WHERE user_id = :userId",
		nativeQuery = true
	)
	void detachUser(@Param("userId") Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT o FROM Order o WHERE o.merchantUid = :merchantUid")
	Optional<Order> findByMerchantUidWithLock(@Param("merchantUid") String merchantUid);

	List<Order> findAllByStatusAndCreatedAtBefore(OrderStatus status, LocalDateTime cutoff);

	/**
	 * 주문 excludedOrderId 말고 이 쿠폰을 쓴 주문 중 상태가 statuses 에 드는 것이 있는지 돌려준다. 할인 복구가 "다른 주문이 아직
	 * 이 쿠폰을 쥐고 있는가" 를 확인할 때 쓴다.
	 *
	 * <p>orders.coupon_id 에 인덱스가 없으면 orders 를 훑는다. 환불·만료처럼 복구할 때만 부른다.
	 */
	boolean existsByCouponIdAndStatusInAndIdNot(Long couponId, Collection<OrderStatus> statuses,
		Long excludedOrderId);

	/**
	 * 현재 상태가 expected 일 때만 next 로 바꾼다. 영향 행 수(0 또는 1)로 경합 여부를 판단한다.
	 * 벌크 UPDATE 뒤 영속성 컨텍스트가 비워지므로 이후 조회는 DB 의 최신 값을 읽는다.
	 *
	 * <p>엔티티의 상태 전이 메서드({@code Order.markExpired} 등)를 거치지 않으므로, 전이 규칙을 바꿀 때는
	 * {@link OrderStatus} 전이 표와 호출 측의 expected/next 조합을 함께 맞춘다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Order o SET o.status = :next WHERE o.id = :id AND o.status = :expected")
	int updateStatusIf(@Param("id") Long id, @Param("expected") OrderStatus expected,
		@Param("next") OrderStatus next);
}
