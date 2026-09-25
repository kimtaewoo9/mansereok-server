package com.mansereok.server.domain.interpret.repository;

import com.mansereok.server.domain.interpret.entity.Result;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ResultRepository extends JpaRepository<Result, Long> {

	List<Result> findAllByUserIdOrderByCreatedAtDesc(Long userId);

	Optional<Result> findByPaymentId(Long paymentId);

	/**
	 * 정보 입력 대기(INPUT_REQUIRED)인 결과만 해석 중(PROCESSING)으로 바꾸고, 바꾼 행 수(0 또는 1)를 돌려준다.
	 *
	 * <p>읽고, 상태를 보고, 쓰는 세 단계를 UPDATE 한 문장으로 합쳤다. 같은 결제로 요청이 동시에 와도 DB 가 한 요청만 1 을 돌려주므로
	 * 해석은 한 번만 시작된다. uk_results_payment_id 로 그 결제의 행 하나만 잠근다. 벌크 UPDATE 는 @PreUpdate 를 거치지 않아
	 * 오래 멈춘 결과를 찾는 기준인 updatedAt 을 직접 넣는다. 앞서 바꾼 엔티티는 먼저 DB 로 보내고, 끝나면 영속성 컨텍스트를 비워
	 * 낡은 상태를 다시 읽지 않게 한다. 호출자의 트랜잭션 안에서 부른다.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("UPDATE Result r"
		+ " SET r.status = com.mansereok.server.domain.interpret.entity.ResultStatus.PROCESSING, r.updatedAt = :now"
		+ " WHERE r.paymentId = :paymentId"
		+ " AND r.status = com.mansereok.server.domain.interpret.entity.ResultStatus.INPUT_REQUIRED")
	int markProcessingIfInputRequired(@Param("paymentId") Long paymentId, @Param("now") LocalDateTime now);

	boolean existsByPaymentId(Long paymentId);

	@Transactional
	@Modifying(clearAutomatically = true)
	@Query("UPDATE Result r SET r.ogImageUrl = :ogImageUrl WHERE r.id = :id")
	void updateOgImageUrl(@Param("id") Long id, @Param("ogImageUrl") String ogImageUrl);

	// 회원 탈퇴 때 부른다. 엔티티를 읽지 않고 DELETE 한 번으로 지운다. 결과 표에는 삭제 콜백도 연관도 없어 엔티티를 거칠 이유가 없다.
	// 트랜잭션은 부르는 쪽(UserService.deleteUser)의 것을 쓰고, 그 안에서 앞서 바꾼 내용은 먼저 DB 로 보낸다.
	// idx_results_user_id 를 타서 그 사용자의 행만 잠근다. 운영에 이 인덱스가 생긴 뒤에만 배포한다.
	// 선언 줄은 CompatibilityResultRepository 와 같게 그대로 둔다(그쪽은 @Param 을 붙이면 결제 스택과 병합 충돌이 난다).
	// 그래서 @Param 대신 -parameters 컴파일 옵션이 필요 없는 ?1 로 묶는다.
	@Modifying(flushAutomatically = true)
	@Query("DELETE FROM Result r WHERE r.userId = ?1")
	void deleteAllByUserId(Long userId);

	List<Result> findByPaymentIdIn(List<Long> paymentIds);
}
