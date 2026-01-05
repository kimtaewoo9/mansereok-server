package com.mansereok.server.domain.coupon.repository;

import com.mansereok.server.domain.coupon.entity.CouponTemplate;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CouponTemplateRepository extends JpaRepository<CouponTemplate, Long> {

	@Query("SELECT t, " +
		"       CASE WHEN c.id IS NOT NULL THEN true ELSE false END " + // 쿠폰이 있으면 true
		"FROM CouponTemplate t " +
		"LEFT JOIN Coupon c ON t.id = c.templateId AND c.userId = :userId " + // 1:1 매칭 (Left Join)
		"WHERE t.issueStartDate <= CURRENT_TIMESTAMP " +
		"  AND t.issueEndDate >= CURRENT_TIMESTAMP")
	List<Object[]> findAllWithIssueStatus(@Param("userId") Long userId);

	// 선착순 발급 시 동시성 제어를 위한 비관적 락 조회
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT t FROM CouponTemplate t WHERE t.id = :id")
	Optional<CouponTemplate> findByIdWithLock(@Param("id") Long id);
}
