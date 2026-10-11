package com.mansereok.server.domain.payment.repository;

import com.mansereok.server.domain.payment.entity.Payment;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByImpUid(String impUid);

	/**
	 * 환불 경로용 행 잠금 조회. imp_uid 가 UNIQUE 라 결제 행 하나만 잠그고, 뒤진 동시 요청은 앞선 트랜잭션이
	 * 커밋한 최신 상태(CANCEL_REQUESTED·CANCELLED)를 읽는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Payment p WHERE p.impUid = :impUid")
	Optional<Payment> findByImpUidWithLock(@Param("impUid") String impUid);

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
