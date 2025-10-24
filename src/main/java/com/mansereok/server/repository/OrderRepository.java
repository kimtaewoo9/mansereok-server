package com.mansereok.server.repository;

import com.mansereok.server.entity.Order;
import com.mansereok.server.entity.OrderStatus;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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

	boolean existsByUserIdAndSubCategoryIdAndStatus(Long userId, Long subCategoryId,
		OrderStatus status);
}
