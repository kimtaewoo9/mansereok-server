package com.mansereok.server.domain.order.repository;

import com.mansereok.server.domain.order.entity.Order;
import java.util.List;
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

	Optional<Order> findByPaymentPkId(Long paymentPkId);

	@Query("SELECT o FROM Order o "
		+ "WHERE o.userId = :userId "
		+ "AND o.subCategoryId = :subCategoryId "
		+ "AND o.status = com.mansereok.server.domain.order.entity.OrderStatus.PAID "
		+ "ORDER BY o.paidAt DESC")
	List<Order> findPaidOrdersForReview(
		@Param("userId") Long userId,
		@Param("subCategoryId") Long subCategoryId
	);
}
