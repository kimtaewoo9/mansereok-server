package com.mansereok.server.domain.coupon.repository;

import com.mansereok.server.domain.coupon.entity.CouponTemplate;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CouponTemplateRepository extends JpaRepository<CouponTemplate, Long> {

	/**
	 * 지금(now) 발급 기간인 쿠폰 템플릿과, 그 템플릿의 쿠폰을 이 사용자가 이미 받았는지를 함께 읽는다. 한 사용자는 한 템플릿의
	 * 쿠폰을 한 장만 가지므로(uk_coupons_user_template) 템플릿마다 한 행이 나온다.
	 */
	@Query("SELECT new com.mansereok.server.domain.coupon.repository.CouponEventRow(t, "
		+ "       CASE WHEN c.id IS NOT NULL THEN true ELSE false END) "
		+ "FROM CouponTemplate t "
		+ "LEFT JOIN Coupon c ON t.id = c.templateId AND c.userId = :userId "
		+ "WHERE t.issueStartDate <= :now "
		+ "  AND t.issueEndDate >= :now")
	List<CouponEventRow> findAllWithIssueStatus(@Param("userId") Long userId, @Param("now") LocalDateTime now);

	// 선착순 발급 시 동시성 제어를 위한 비관적 락 조회
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT t FROM CouponTemplate t WHERE t.id = :id")
	Optional<CouponTemplate> findByIdWithLock(@Param("id") Long id);
}
