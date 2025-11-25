package com.mansereok.server.domain.payment.repository;

import com.mansereok.server.domain.payment.entity.Payment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByImpUid(String impUid);

	@Query(
		value = "SELECT * "
			+ "FROM payments "
			+ "WHERE user_id = :userId "
			+ "ORDER BY created_at DESC",
		nativeQuery = true
	)
	List<Payment> findAllByUserIdOrderByCreatedAtDesc(
		@Param("userId") Long userId
	);

	@Modifying(clearAutomatically = true)
	@Query(
		value = "UPDATE payments SET user_id = NULL WHERE user_id = :userId",
		nativeQuery = true)
	void detachUser(@Param("userId") Long userId);
}
