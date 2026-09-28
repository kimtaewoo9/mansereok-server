package com.mansereok.server.domain.discount.repository;

import com.mansereok.server.domain.discount.entity.DiscountCode;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface DiscountCodeRepository extends JpaRepository<DiscountCode, Long> {

	Optional<DiscountCode> findByCodeAndIsActiveTrue(String code);

	/**
	 * 코드로 할인 코드를 찾으면서 그 행에 쓰기 잠금(SELECT ... FOR UPDATE)을 건다. 잠금은 호출한 트랜잭션이 끝날 때 풀린다.
	 *
	 * <p>사용 횟수를 읽고 바꾸는 곳에서만 쓴다. 단순 조회에 쓰면 결제 경로와 같은 행을 두고 기다리게 된다. 트랜잭션 밖에서 부르면
	 * 잠금이 곧바로 풀려 의미가 없다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from DiscountCode d where d.code = :code")
	Optional<DiscountCode> findByCodeForUpdate(@Param("code") String code);
}
