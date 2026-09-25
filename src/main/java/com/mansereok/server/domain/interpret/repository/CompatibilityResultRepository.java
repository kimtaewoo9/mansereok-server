package com.mansereok.server.domain.interpret.repository;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface CompatibilityResultRepository extends JpaRepository<CompatibilityResult, Long> {

	// 사주를 검색을 할때 .. 일반 사주랑, 궁합 사주, 삼각 관계 사주 따로 구헤야할듯 ..
	List<CompatibilityResult> findByUserIdOrderByCreatedAtDesc(Long userId);

	Optional<CompatibilityResult> findByPaymentId(Long paymentId);

	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("UPDATE CompatibilityResult c SET c.ogImageUrl = :ogImageUrl WHERE c.id = :id")
	void updateOgImageUrl(@Param("id") Long id, @Param("ogImageUrl") String ogImageUrl);

	void deleteAllByUserId(Long userId);

	List<CompatibilityResult> findByPaymentIdIn(List<Long> paymentIds);

	/**
	 * 환불용 조건부 삭제. 결제의 결과가 status 일 때만 지우고 지운 행 수를 돌려준다. 영속성 컨텍스트가 아니라 DB 의 현재 상태로
	 * 판단하므로, open-in-view 로 앞 트랜잭션에서 읽어 둔 엔티티가 남아 있어도 그 낡은 상태를 보지 않는다.
	 *
	 * <p>영속성 컨텍스트를 비우지 않는다(clearAutomatically 를 쓰지 않는다). 환불이 이 삭제 앞에서 바꾼 결제·주문 엔티티가 아직
	 * DB 에 쓰이지 않았을 수 있어, 비우면 그 변경이 사라진다. 호출자의 트랜잭션 안에서 부른다.
	 */
	@Modifying
	@Query("DELETE FROM CompatibilityResult c WHERE c.paymentId = :paymentId AND c.status = :status")
	int deleteByPaymentIdAndStatus(@Param("paymentId") Long paymentId, @Param("status") ResultStatus status);

	/**
	 * 환불용 행 잠금 조회. 잠그지 않는 읽기는 REPEATABLE READ 에서 트랜잭션의 첫 읽기 시점 스냅샷을 보므로, 결제 행 잠금을
	 * 기다리는 동안 해석 시작이 커밋한 PROCESSING 을 보지 못한다. 잠금 조회는 가장 최근에 커밋된 행을 읽는다.
	 *
	 * <p>이미 영속성 컨텍스트에 있는 엔티티면 행은 잠그지만 필드는 새로 읽지 않는다. 행이 있는지는 DB 대로 돌려준다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM CompatibilityResult c WHERE c.paymentId = :paymentId")
	Optional<CompatibilityResult> findByPaymentIdForUpdate(@Param("paymentId") Long paymentId);

	/**
	 * 환불용 존재 확인. 잠그지 않고 읽는다. 환불은 이 결과로 결과 행이 있는 표를 먼저 가린 뒤 그 표에서만 잠금 조회·조건부 삭제를
	 * 한다. payment_id 가 UNIQUE 라, 행이 없는 값을 잠가 읽거나 조건부로 지우면 REPEATABLE READ 에서 그 자리의 간격이 잠겨 다른
	 * 결제의 초기 결과 INSERT 가 기다린다.
	 */
	boolean existsByPaymentId(Long paymentId);
}
