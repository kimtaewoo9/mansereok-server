package com.mansereok.server.repository;

import com.mansereok.server.entity.CompatibilityResult;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CompatibilityResultRepository extends JpaRepository<CompatibilityResult, Long> {

	// 사주를 검색을 할때 .. 일반 사주랑, 궁합 사주, 삼각 관계 사주 따로 구헤야할듯 ..
	List<CompatibilityResult> findByUserIdOrderByCreatedAtDesc(Long userId);

	Optional<CompatibilityResult> findByPaymentId(Long paymentId);
}
