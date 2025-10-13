package com.mansereok.server.repository;

import com.mansereok.server.entity.Payment;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

	@Query(
		value = "select * "
			+ "from payments "
			+ "where user_id =:userId "
			+ "order by created_at desc",
		nativeQuery = true
	)
	List<Payment> findAllByUserIdOrderByCreatedAtDesc(
		@Param("userId") Long userId
	);
}
