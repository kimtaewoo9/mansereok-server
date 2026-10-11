package com.mansereok.server.domain.payment.repository;

import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
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
public interface PaymentRepository extends JpaRepository<Payment, Long> {

	Optional<Payment> findByImpUid(String impUid);

	/**
	 * 환불 경로용 행 잠금 조회. imp_uid 가 UNIQUE 라 결제 행 하나만 잠그고, 뒤진 동시 요청은 앞선 트랜잭션이
	 * 커밋한 최신 상태(CANCEL_REQUESTED·CANCELLED)를 읽는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Payment p WHERE p.impUid = :impUid")
	Optional<Payment> findByImpUidWithLock(@Param("impUid") String impUid);

	/**
	 * 대사용 창 조회. createdAt 이 LocalDateTime.now() 로 기록되므로 같은 시간대의 LocalDateTime 으로 자른다.
	 */
	List<Payment> findAllByCreatedAtGreaterThanEqualAndCreatedAtLessThan(LocalDateTime from,
		LocalDateTime until);

	/**
	 * 대사용 상태 조회. 환불 도중 CANCEL_REQUESTED 로 굳은 결제는 언제 만들어졌든 찾아내야 해서 창을 두지 않는다.
	 */
	List<Payment> findAllByStatus(PaymentStatus status);

	/**
	 * 대사용 impUid 조회. 전날 결제되고 대상일에 취소된 건은 창(createdAt) 밖이라, PG 목록에 잡힌 impUid 는
	 * 창과 별개로 한 번 더 찾아본다.
	 */
	List<Payment> findAllByImpUidIn(Collection<String> impUids);

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
