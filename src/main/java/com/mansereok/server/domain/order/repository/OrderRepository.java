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
	 * <p>잠그지 않는 읽기다. 호출한 트랜잭션의 스냅샷을 읽고, 다른 트랜잭션이 잠근 주문 행을 기다리지 않는다. 그래서 아직 커밋되지
	 * 않은 늦은 결제 확정은 보지 못한다(OrderDiscountRestorer#restore 설명). 잠금 읽기(FOR SHARE)로 바꾸면 orders.coupon_id
	 * 인덱스가 없는 지금은 orders 를 모두 잠그며 훑어, 늦은 결제와 상관없이 쿠폰이 서로 다른 주문의 환불·만료끼리도 교착된다. 바꾸려면
	 * coupon_id 인덱스를 먼저 두고 그 겹침에서 교착이 없는지 LatePaidDiscountOverlapMySqlTest 로 확인한다.
	 *
	 * <p>orders.coupon_id 에 인덱스가 없으면 orders 를 훑는다. 환불·만료·웹훅 실패 기록처럼 쿠폰을 되돌릴 때만 부른다.
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
