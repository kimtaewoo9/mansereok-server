package com.mansereok.server.domain.interpret.repository;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
