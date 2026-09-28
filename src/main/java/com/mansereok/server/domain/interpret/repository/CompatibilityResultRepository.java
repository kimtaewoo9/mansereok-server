package com.mansereok.server.domain.interpret.repository;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
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

	/**
	 * 정보 입력 대기(INPUT_REQUIRED)인 궁합 결과만 해석 중(PROCESSING)으로 바꾸고, 바꾼 행 수(0 또는 1)를 돌려준다.
	 * ResultRepository.markProcessingIfInputRequired 와 같은 규칙이다. uk_compatibility_results_payment_id 로 한 행만 잠근다.
	 * 호출자의 트랜잭션 안에서 부른다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE CompatibilityResult c"
		+ " SET c.status = com.mansereok.server.domain.interpret.entity.ResultStatus.PROCESSING, c.updatedAt = :now"
		+ " WHERE c.paymentId = :paymentId"
		+ " AND c.status = com.mansereok.server.domain.interpret.entity.ResultStatus.INPUT_REQUIRED")
	int markProcessingIfInputRequired(@Param("paymentId") Long paymentId, @Param("now") LocalDateTime now);

	boolean existsByPaymentId(Long paymentId);

	/** 결과 ID 로 행을 읽으며 쓰기 잠금을 건다. ResultRepository.findByIdForUpdate 와 같은 쓰임이다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM CompatibilityResult c WHERE c.id = :id")
	Optional<CompatibilityResult> findByIdForUpdate(@Param("id") Long id);

	/**
	 * 결제 ID 로 행을 읽으며 쓰기 잠금을 건다. ResultRepository.findByPaymentIdForUpdate 와 같은 쓰임이고,
	 * uk_compatibility_results_payment_id 로 한 행만 잠근다. 결제 스택에도 같은 이름·같은 쿼리의 메서드가 있어, 합칠 때 하나만 남긴다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT c FROM CompatibilityResult c WHERE c.paymentId = :paymentId")
	Optional<CompatibilityResult> findByPaymentIdForUpdate(@Param("paymentId") Long paymentId);

	/**
	 * 해석을 staleBefore 보다 전에 시작해 아직 해석 중(PROCESSING)인 궁합 결과의 ID 를 잠그지 않고 읽는다.
	 * ResultRepository.findIdsProcessingUpdatedBefore 와 같은 규칙이고 같은 까닭으로 되돌리기와 나눈다. updated_at 이 NULL 인
	 * 행(컬럼을 더하기 전의 행을 채우지 않은 경우)은 비교가 참이 되지 않아 읽지 않는다.
	 */
	@Query("SELECT c.id FROM CompatibilityResult c"
		+ " WHERE c.status = com.mansereok.server.domain.interpret.entity.ResultStatus.PROCESSING"
		+ " AND c.updatedAt < :staleBefore")
	List<Long> findIdsProcessingUpdatedBefore(@Param("staleBefore") LocalDateTime staleBefore);

	/**
	 * 궁합 결과 id 가 아직 해석 중이고 해석을 staleBefore 보다 전에 시작했으면 정보 입력 대기(INPUT_REQUIRED)로 되돌리고, 되돌린 행
	 * 수(0 또는 1)를 돌려준다. ResultRepository.revertIfProcessingUpdatedBefore 와 같은 규칙이다.
	 */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE CompatibilityResult c"
		+ " SET c.status = com.mansereok.server.domain.interpret.entity.ResultStatus.INPUT_REQUIRED, c.updatedAt = :now"
		+ " WHERE c.id = :id"
		+ " AND c.status = com.mansereok.server.domain.interpret.entity.ResultStatus.PROCESSING"
		+ " AND c.updatedAt < :staleBefore")
	int revertIfProcessingUpdatedBefore(@Param("id") Long id, @Param("staleBefore") LocalDateTime staleBefore,
		@Param("now") LocalDateTime now);

	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("UPDATE CompatibilityResult c SET c.ogImageUrl = :ogImageUrl WHERE c.id = :id")
	void updateOgImageUrl(@Param("id") Long id, @Param("ogImageUrl") String ogImageUrl);

	// 회원 탈퇴 때 부른다. 엔티티를 읽지 않고 DELETE 한 번으로 지운다. 결과 표에는 삭제 콜백도 연관도 없어 엔티티를 거칠 이유가 없다.
	// 트랜잭션은 부르는 쪽(UserService.deleteUser)의 것을 쓰고, 그 안에서 앞서 바꾼 내용은 먼저 DB 로 보낸다.
	// idx_compatibility_results_user_id 를 타서 그 사용자의 행만 잠근다. 운영에 이 인덱스가 생긴 뒤에만 배포한다.
	// 선언 줄은 결제 스택이 바로 다음 줄을 고쳐 두어 그대로 둔다(@Param 을 붙이면 병합 충돌). 그래서 -parameters 컴파일 옵션이
	// 필요 없는 ?1 로 묶는다.
	@Modifying(flushAutomatically = true)
	@Query("DELETE FROM CompatibilityResult c WHERE c.userId = ?1")
	void deleteAllByUserId(Long userId);
}
