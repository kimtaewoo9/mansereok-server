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
import org.springframework.transaction.annotation.Propagation;
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
		Coupon coupon = couponRepository.findByIdWithLock(couponId)
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

	/**
	 * 주문을 만들 때 쿠폰을 사용 처리한다.
	 *
	 * <p>쿠폰 행을 스스로 잠가(SELECT ... FOR UPDATE) 읽는다. 같은 쿠폰으로 동시에 들어온 두 요청이 둘 다 미사용으로 읽고 둘 다
	 * 사용 처리하지 않게 하기 위해서다. 같은 트랜잭션에서 {@link #validateAndCalculateCoupon} 이 이미 잠갔다면 이미 쥔 잠금이라
	 * 더 기다리지 않는다. 잠금은 호출자의 트랜잭션이 끝날 때 풀린다.
	 */
	@Transactional
	public void useCoupon(Long couponId) {
		Coupon coupon = couponRepository.findByIdWithLock(couponId)
			.orElseThrow(() -> new PaymentException("쿠폰 없음"));
		coupon.use();
	}

	/**
	 * 만료 뒤 늦게 결제된 주문 몫으로, 만료 때 돌려놓은 쿠폰을 다시 사용 처리한다.
	 *
	 * <p>쿠폰 행을 잠가 읽은 뒤 미사용이면 사용 처리한다. 그사이 다른 주문이 이 쿠폰을 이미 썼다면 아무것도 바꾸지 않고 false 를
	 * 돌려준다. 결제는 이미 끝났으므로 예외로 확정을 되돌리지 않고, 호출자가 운영 알림을 보낸다. 쿠폰 기간은 보지 않는다
	 * ({@link Coupon#useForPaidOrder()}).
	 *
	 * <p>호출자(결제 확정)가 주문 행을 잠근 트랜잭션 안에서 부른다. 그래서 이 경로는 주문 행 → 쿠폰 행 순서로 잠그고, 쿠폰 행 → 주문
	 * INSERT 순서인 주문 생성과 반대다. orders.merchant_uid 인덱스가 없으면 둘이 교착될 수 있다(OrderDiscountRestorer 클래스 설명).
	 * 잠금은 호출자의 트랜잭션이 끝날 때 풀린다. 트랜잭션 밖에서 부르면 이 전제가 깨지므로 {@link Propagation#MANDATORY} 로 진행 중인
	 * 트랜잭션이 없으면 IllegalTransactionStateException 을 던진다.
	 *
	 * @return 이 호출로 사용 처리했으면 true, 다른 주문이 이미 쓰고 있어 그대로 두었으면 false
	 * @throws PaymentException 쿠폰이 없을 때
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean claimForPaidOrder(Long couponId) {
		Coupon coupon = couponRepository.findByIdWithLock(couponId)
			.orElseThrow(() -> new PaymentException("쿠폰 정보를 찾을 수 없습니다."));
		if (coupon.isUsed()) {
			return false;
		}
		coupon.useForPaidOrder();
		return true;
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

	/**
	 * 주문이 쿠폰을 놓을 때(만료·환불·웹훅 실패 기록) 쿠폰을 미사용으로 되돌린다.
	 *
	 * <p>쿠폰 행을 잠가(SELECT ... FOR UPDATE) 가장 최근에 커밋된 상태를 읽는다. 잠그지 않고 읽으면 트랜잭션 스냅샷의 값을 읽고,
	 * 그사이 다른 트랜잭션이 바꾼 쿠폰을 덮어쓴다.
	 *
	 * <p>호출자(OrderDiscountRestorer.restore)는 같은 트랜잭션에서 이 쿠폰을 쥔 다른 주문이 없음을 잠금 읽기로 먼저 확인한다. 그
	 * 확인이 되돌리기까지 이어지려면 두 잠금이 같은 트랜잭션에 있어야 하므로 {@link Propagation#MANDATORY} 로 진행 중인 트랜잭션이
	 * 없으면 IllegalTransactionStateException 을 던진다.
	 *
	 * @throws PaymentException 쿠폰이 없을 때
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void restoreCoupon(Long couponId) {
		if (couponId == null) {
			return;
		}

		Coupon coupon = couponRepository.findByIdWithLock(couponId)
			.orElseThrow(() -> new PaymentException("쿠폰 정보를 찾을 수 없습니다."));

		// 사용된 상태라면 복구
		if (coupon.isUsed()) {
			coupon.restore();
		}
	}
}
