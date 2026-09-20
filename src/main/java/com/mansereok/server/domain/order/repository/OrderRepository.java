package com.mansereok.server.domain.order.repository;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
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
	 * 현재 상태가 expected 일 때만 next 로 바꾼다. 영향 행 수(0 또는 1)로 경합 여부를 판단한다.
	 * 벌크 UPDATE 뒤 영속성 컨텍스트가 비워지므로 이후 조회는 DB 의 최신 값을 읽는다.
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Order o SET o.status = :next WHERE o.id = :id AND o.status = :expected")
	int updateStatusIf(@Param("id") Long id, @Param("expected") OrderStatus expected,
		@Param("next") OrderStatus next);
}
