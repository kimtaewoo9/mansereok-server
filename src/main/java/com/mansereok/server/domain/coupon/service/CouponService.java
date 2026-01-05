package com.mansereok.server.domain.coupon.service;

import com.mansereok.server.domain.coupon.dto.CouponEventDto;
import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.coupon.entity.CouponTemplate;
import com.mansereok.server.domain.coupon.repository.CouponRepository;
import com.mansereok.server.domain.coupon.repository.CouponTemplateRepository;
import com.mansereok.server.domain.discount.service.DiscountCodeService.DiscountValidationResult;
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponService {

	private final CouponRepository couponRepository;
	private final CouponTemplateRepository couponTemplateRepository;

	@Transactional
	public void downloadCoupon(Long userId, Long templateId) {
		// 1. 템플릿 조회
		CouponTemplate template = couponTemplateRepository.findByIdWithLock(templateId)
			.orElseThrow(() -> new PaymentException("존재하지 않는 쿠폰 이벤트입니다."));

		// 2. 이벤트 기간 검증
		LocalDateTime now = LocalDateTime.now();
		if (now.isBefore(template.getIssueStartDate()) || now.isAfter(template.getIssueEndDate())) {
			throw new PaymentException("발급 기간이 아닙니다.");
		}

		// 3. 중복 발급 검증 (이미 받은 건지 확인)
		if (couponRepository.existsByUserIdAndTemplateId(userId, templateId)) {
			throw new PaymentException("이미 발급받은 쿠폰입니다.");
		}

		// 4. 선착순 재고 증가 및 검증 (Template 엔티티 내부 로직)
		template.incrementIssueCount();

		// 5. 실제 쿠폰 생성 및 저장
		Coupon coupon = Coupon.createFromTemplate(template, userId);
		couponRepository.save(coupon);
	}

	// 내 쿠폰함 조회
	@Transactional(readOnly = true)
	public List<Coupon> getMyCoupons(Long userId) {
		return couponRepository.findAllAvailableByUserId(userId);
	}

	// 결제 시 쿠폰 적용 및 검증
	@Transactional
	public DiscountValidationResult validateAndCalculateCoupon(Long couponId, Long userId,
		int originalAmount) {
		Coupon coupon = couponRepository.findById(couponId)
			.orElseThrow(() -> new PaymentException("존재하지 않는 쿠폰입니다."));

		if (!coupon.getUserId().equals(userId)) {
			throw new PaymentException("본인의 쿠폰만 사용할 수 있습니다.");
		}

		// 유효성 검사 (만료일, 사용여부 등)는 use() 호출 시나 별도 validate()에서 수행
		// 여기서는 금액 계산을 위해 미리 검증
		if (coupon.isUsed()) {
			throw new PaymentException("이미 사용한 쿠폰입니다.");
		}

		int finalAmount = coupon.applyDiscount(originalAmount);

		// 결과 반환 (기존 DiscountValidationResult 재활용하거나 새로 만듦)
		// 여기서는 편의상 Coupon 엔티티를 Object로 넘기거나 별도 DTO 사용 권장
		// 기존 Result 클래스를 재사용하기 위해 약간의 수정이 필요할 수 있음
		return new DiscountValidationResult(finalAmount, coupon.getName(), null);
	}

	// 쿠폰 사용 처리 (결제 완료 후 호출)
	@Transactional
	public void useCoupon(Long couponId) {
		Coupon coupon = couponRepository.findById(couponId)
			.orElseThrow(() -> new PaymentException("쿠폰 없음"));
		coupon.use();
	}

	@Transactional(readOnly = true)
	public List<CouponEventDto> getCouponEvents(Long userId) {
		List<Object[]> results = couponTemplateRepository.findAllWithIssueStatus(userId);

		// 날짜 포맷터 (예: 2024.12.31)
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy.MM.dd");

		return results.stream()
			.map(row -> {
				CouponTemplate t = (CouponTemplate) row[0];
				boolean isIssued = (boolean) row[1];
				boolean isSoldOut = t.getMaxIssueCount() != null &&
					t.getCurrentIssueCount() >= t.getMaxIssueCount();

				// [수정됨] 유효 기간 텍스트 계산 로직
				String validPeriod;

				if (t.getValidUntil() != null) {
					// 1. 고정 날짜 방식 (예: 2026.12.31 까지)
					validPeriod = t.getValidUntil().format(formatter) + " 까지";

				} else if (t.getValidDaysAfterIssue() != null) {
					// 2. '발급 후 30일' 방식 -> 오늘 받으면 언제까지인지 날짜로 계산해서 보여줌
					// 예: 오늘(1/5) + 30일 = "2024.02.04 까지"
					LocalDate expiredDate = LocalDate.now().plusDays(t.getValidDaysAfterIssue());
					validPeriod = expiredDate.format(formatter) + " 까지";

				} else {
					validPeriod = "기간 제한 없음";
				}

				return new CouponEventDto(
					t.getId(),
					t.getName(),
					t.getDiscountType().toString(),
					t.getDiscountValue(),
					validPeriod, // 계산된 날짜 문자열 전달
					isIssued,
					isSoldOut
				);
			})
			.collect(Collectors.toList());
	}
}
