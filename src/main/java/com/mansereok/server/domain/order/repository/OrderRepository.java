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

	/**
	 * 만료 스캔용. 상태가 status 이고 cutoff 보다 먼저 만든 주문의 id 만 돌려준다. cutoff 와 같은 시각에 만든 주문은 넣지 않는다.
	 *
	 * <p>건별 처리는 id 로 따로 하므로 여기서는 주문 엔티티를 만들지 않는다. (status, created_at) 인덱스에 PK 가 함께 담겨 있어
	 * 인덱스만 읽고 끝난다.
	 */
	@Query("SELECT o.id FROM Order o WHERE o.status = :status AND o.createdAt < :cutoff")
	List<Long> findIdsByStatusAndCreatedAtBefore(@Param("status") OrderStatus status,
		@Param("cutoff") LocalDateTime cutoff);

	/**
	 * 주문 excludedOrderId 말고 이 쿠폰을 쓴 주문 중 상태가 statuses 에 드는 것이 있는지 돌려준다. 할인 복구가 "다른 주문이 아직
	 * 이 쿠폰을 쥐고 있는가" 를 확인할 때 쓴다.
	 *
	 * <p>잠그지 않는 읽기다. 호출한 트랜잭션의 스냅샷을 읽고, 다른 트랜잭션이 잠근 주문 행을 기다리지 않는다. 그래서 아직 커밋되지
	 * 않은 늦은 결제 확정은 보지 못한다(OrderDiscountRestorer#restore 설명). 잠금 읽기(FOR SHARE)로 바꾸면 orders.coupon_id
	 * 인덱스가 없는 DB 에서는 orders 를 모두 잠그며 훑어, 늦은 결제와 상관없이 쿠폰이 서로 다른 주문의 환불·만료끼리도 교착된다.
	 * 바꾸려면 운영에 idx_orders_coupon_id 가 있는지 먼저 확인하고, 그 겹침에서 교착이 없는지 LatePaidDiscountOverlapMySqlTest 로
	 * 확인한다.
	 *
	 * <p>idx_orders_coupon_id 로 그 쿠폰을 쓴 주문만 읽는다. 환불·만료·웹훅 실패 기록처럼 쿠폰을 되돌릴 때만 부른다.
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
