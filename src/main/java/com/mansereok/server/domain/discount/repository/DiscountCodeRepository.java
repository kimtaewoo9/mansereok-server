package com.mansereok.server.domain.discount.repository;

import com.mansereok.server.domain.discount.entity.DiscountCode;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

@Repository
public interface DiscountCodeRepository extends JpaRepository<DiscountCode, Long> {

	Optional<DiscountCode> findByCodeAndIsActiveTrue(String code);

	// Lock을 걸어서 트랜잭션이 끝날때 까지 다른 접근을 막고, 코드를 조회한다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<DiscountCode> findByCode(String code);
}
