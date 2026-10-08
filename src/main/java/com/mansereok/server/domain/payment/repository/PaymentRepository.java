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
	 * 해석 시작용 행 잠금 조회. 해석 API 는 결제 PK 를 받으므로 PK 로 잠근다. 환불도 같은 결제 행을 먼저 잠그므로(imp_uid 로
	 * 찾아도 잠기는 행은 같다) 해석 시작과 환불이 이 행에서 줄을 선다. 뒤진 쪽은 앞선 쪽이 커밋한 최신 상태를 읽는다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM Payment p WHERE p.id = :id")
	Optional<Payment> findByIdWithLock(@Param("id") Long id);

	/**
	 * 대사용 창 조회. createdAt 이 LocalDateTime.now() 로 기록되므로 같은 시간대의 LocalDateTime 으로 자른다.
	 */
	List<Payment> findAllByCreatedAtGreaterThanEqualAndCreatedAtLessThan(LocalDateTime from,
		LocalDateTime until);

	/**
	 * 대사용 상태 조회. 환불이나 늦은 결제 자동 취소 도중 CANCEL_REQUESTED 로 굳은 결제는 언제 만들어졌든 찾아내야 해서 창을 두지 않는다.
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

	/**
	 * 환불 확정 직전에 DB 에 있는 결제 상태를 읽는다. open-in-view 에서는 잠금 조회도 이 요청이 앞서 읽어 둔 엔티티를 그대로
	 * 돌려줘, 다른 요청이 그 사이 커밋한 CANCELLED 가 엔티티에 보이지 않는다. 스칼라 조회는 영속성 컨텍스트를 거치지 않는다.
	 */
	@Query("SELECT p.status FROM Payment p WHERE p.id = :id")
	PaymentStatus findStatusById(@Param("id") Long id);

	/**
	 * 환불 되돌리기용 조건부 전이. 잠금 없이 한 문장으로 바꾸므로, 다른 요청이 그 사이 CANCELLED 로 확정했으면 0 을 돌려주고
	 * 덮어쓰지 않는다. 호출자는 expected 로 CANCEL_REQUESTED, next 로 PAID 만 넘긴다(되돌리기는 이 전이뿐이다).
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("UPDATE Payment p SET p.status = :next WHERE p.id = :id AND p.status = :expected")
	int updateStatusIf(@Param("id") Long id, @Param("expected") PaymentStatus expected,
		@Param("next") PaymentStatus next);
}
