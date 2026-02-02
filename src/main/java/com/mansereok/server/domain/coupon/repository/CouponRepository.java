package com.mansereok.server.domain.coupon.repository;

import com.mansereok.server.domain.coupon.entity.Coupon;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface CouponRepository extends JpaRepository<Coupon, Long> {

	// 내 쿠폰중 .. 사용 가능하고 만료되지 않은 것 찾기
	@Query(
		value = "SELECT * FROM coupons WHERE user_id = :userId AND is_used = false AND expires_at > NOW()",
		nativeQuery = true
	)
	List<Coupon> findAllAvailableByUserId(@Param("userId") Long userId);

	boolean existsByUserIdAndTemplateId(Long userId, Long templateId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Coupon c where c.id = :id")
	Optional<Coupon> findByIdWithLock(@Param("id") Long id);
}
