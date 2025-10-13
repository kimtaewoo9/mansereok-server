package com.mansereok.server.repository;

import com.mansereok.server.entity.Payment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

	// ✅ 추가 필요!
	Optional<Payment> findByImpUid(String impUid);
	
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
