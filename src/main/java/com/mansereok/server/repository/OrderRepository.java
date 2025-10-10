package com.mansereok.server.repository;

import com.mansereok.server.entity.Order;
import com.mansereok.server.entity.OrderStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrderRepository extends JpaRepository<Order, Long> {

	Optional<Order> findByMerchantUid(String merchantUid);

	// 주문 내역 조회 (중복 구매 방지용)
	boolean existsByUserIdAndSubCategoryIdAndStatus(Long userId, Long subCategoryId,
		OrderStatus status);
}
