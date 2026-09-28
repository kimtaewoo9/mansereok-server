package com.mansereok.server.domain.coupon.repository;

import com.mansereok.server.domain.coupon.entity.Coupon;
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
public interface CouponRepository extends JpaRepository<Coupon, Long> {

	/**
	 * 사용자의 쿠폰 중 아직 쓰지 않았고 지금(now) 만료되지 않은 쿠폰. 만료 판정은 Coupon.isExpired 와 같다. 만료 시각 그 순간까지는
	 * 쓸 수 있고, 만료 시각이 없는(NULL) 쿠폰은 늘 들어간다.
	 *
	 * <p>지금 시각은 DB 의 NOW() 가 아니라 호출자가 앱의 Clock 으로 정해 넘긴다. 만료 시각도 앱 시각으로 저장하므로, DB 시간대가 앱과
	 * 달라도 같은 기준으로 비교한다.
	 */
	@Query(
		value = "SELECT * FROM coupons WHERE user_id = :userId AND is_used = false"
			+ " AND (expires_at IS NULL OR expires_at >= :now)",
		nativeQuery = true
	)
	List<Coupon> findAllAvailableByUserId(@Param("userId") Long userId, @Param("now") LocalDateTime now);

	boolean existsByUserIdAndTemplateId(Long userId, Long templateId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Coupon c where c.id = :id")
	Optional<Coupon> findByIdWithLock(@Param("id") Long id);
}
