package com.mansereok.server.domain.interpret.repository;

import com.mansereok.server.domain.interpret.entity.Result;
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
