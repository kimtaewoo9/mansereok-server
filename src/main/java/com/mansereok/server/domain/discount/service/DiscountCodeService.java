package com.mansereok.server.domain.discount.service;

import com.mansereok.server.domain.discount.dto.request.DiscountCheckRequest;
import com.mansereok.server.domain.discount.dto.response.DiscountCheckResponse;
import com.mansereok.server.domain.discount.entity.DiscountCode;
import com.mansereok.server.domain.discount.repository.DiscountCodeRepository;
import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.global.exception.PaymentException;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DiscountCodeService {

	private final DiscountCodeRepository discountCodeRepository;
	private final SubCategoryRepository subCategoryRepository;
	private final Clock clock;

	/**
	 * 코드 만료를 판정할 "지금" 을 clock 으로 정한다. 스프링은 이 생성자로 ClockConfig 의 Clock 빈을 넣는다.
	 */
	@Autowired
	public DiscountCodeService(DiscountCodeRepository discountCodeRepository,
		SubCategoryRepository subCategoryRepository, Clock clock) {
		this.discountCodeRepository = discountCodeRepository;
		this.subCategoryRepository = subCategoryRepository;
		this.clock = clock;
	}

	/**
	 * 시스템 기본 시간대의 시계로 "지금" 을 정한다. 시각을 고정할 필요가 없는 곳(리포지토리를 목으로 바꾼 테스트 등)에서 쓴다.
	 */
	public DiscountCodeService(DiscountCodeRepository discountCodeRepository,
		SubCategoryRepository subCategoryRepository) {
		this(discountCodeRepository, subCategoryRepository, Clock.systemDefaultZone());
	}

	@Transactional(readOnly = true)
	public DiscountCheckResponse checkDiscount(DiscountCheckRequest request) {
		SubCategory subCategory = subCategoryRepository.findById(request.getSubCategoryId())
			.orElseThrow(() -> new EntityNotFoundException("상품을 찾을 수 없습니다."));

		int originalAmount = subCategory.getPrice();

		// 할인 코드 따로 입력 안한 경우 .
		if (request.getDiscountCode() == null || request.getDiscountCode().isBlank()) {
			return new DiscountCheckResponse(originalAmount, originalAmount, 0,
				"할인 코드가 입력되지 않았습니다.");
		}

		DiscountCode discountCode = discountCodeRepository.findByCodeAndIsActiveTrue(
				request.getDiscountCode())
			.orElseThrow(() -> new PaymentException("유효하지 않은 코드입니다."));

		discountCode.validate(LocalDateTime.now(clock));

		// 특정 상품에만 사용할 수 있는지 검증하는 로직
		validateSubCategory(discountCode, request.getSubCategoryId());

		int discountedAmount = discountCode.applyDiscount(originalAmount);
		int discountAmount = originalAmount - discountedAmount;

		return new DiscountCheckResponse(
			originalAmount,
			discountedAmount, // 할인된 최종 금액 금액
			discountAmount,
			"할인 코드가 유효합니다."
		);
	}

	@Transactional
	public DiscountValidationResult validateAndCalculateDiscountForPayment(
		String code,
		int originalAmount,
		Long subCategoryId
	) {
		// 1. 코드가 없으면 할인 없이 통과
		if (code == null || code.isBlank()) {
			return new DiscountValidationResult(originalAmount, null, null);
		}

		// 2. 행을 잠가 조회한다. 잠금은 이 트랜잭션(주문 생성)이 끝날 때까지 이어져 incrementUsage 까지 덮는다.
		DiscountCode discountCode = discountCodeRepository.findByCodeForUpdate(code)
			.orElseThrow(() -> new PaymentException("유효하지 않은 코드입니다."));

		// 3. 최종 검증 (락이 걸린 상태에서)
		discountCode.validate(LocalDateTime.now(clock));

		// 3_1. 특정 상품에만 쓸 수 있는 코드인지 여기서도 검증 .
		validateSubCategory(discountCode, subCategoryId);

		// 4. 최종 할인액 계산
		int finalAmount = discountCode.applyDiscount(originalAmount);

		// 5. 락이 걸린 엔티티와 최종 금액, 코드명을 반환
		return new DiscountValidationResult(finalAmount, code, discountCode);
	}

	/**
	 * 주문을 만들 때 할인 코드 사용 횟수를 1 올린다.
	 *
	 * <p>이 메서드는 스스로 잠그지 않는다. 호출자가 같은 트랜잭션에서 {@link #validateAndCalculateDiscountForPayment} 로 행을 잠가
	 * 받은 엔티티를 넘겨야 한다. 그래야 검증(최대 횟수 확인)과 증가 사이에 다른 주문이 끼어들지 못한다. 트랜잭션 밖에서 부르면
	 * 잠금이 이미 풀린 뒤라 이 전제가 깨지므로, {@link Propagation#MANDATORY} 로 진행 중인 트랜잭션이 없으면
	 * IllegalTransactionStateException 을 던진다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void incrementUsage(DiscountCode discountCode) {
		if (discountCode == null) {
			return;
		}

		// 호출자가 잠가 둔 엔티티의 횟수 증가
		discountCode.incrementUsage();
		discountCodeRepository.save(discountCode); // 변경 감지(Dirty checking)
	}

	/**
	 * 만료 뒤 늦게 결제된 주문 몫으로, 만료 때 돌려놓은 사용 횟수를 다시 올린다. 그사이 다른 주문이 자리를 가져가 최대 횟수에 닿았으면
	 * 올리지 않고 false 를 돌려준다. 호출자는 그 결제를 확정하지 않고 취소한다.
	 *
	 * <p>호출자(결제 확정)가 주문 행을 잠근 트랜잭션 안에서 부른다. 그래서 이 경로는 주문 행 → 할인 코드 행 순서로 잠그고, 할인 코드
	 * 행 → 주문 INSERT 순서인 주문 생성과 반대다. orders.merchant_uid 인덱스가 없으면 둘이 교착될 수 있다(OrderDiscountRestorer
	 * 클래스 설명). 트랜잭션 밖에서 부르면 이 전제가 깨지므로 {@link Propagation#MANDATORY} 로 진행 중인 트랜잭션이 없으면
	 * IllegalTransactionStateException 을 던진다.
	 *
	 * @return 다시 올렸으면 true, 최대 횟수에 닿아 그대로 두었으면 false
	 * @throws PaymentException 코드가 없을 때
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean claimForPaidOrder(String code) {
		DiscountCode discountCode = discountCodeRepository.findByCodeForUpdate(code)
			.orElseThrow(() -> new PaymentException("존재하지 않는 할인 코드입니다."));
		return discountCode.incrementUsageForLatePayment();
	}

	/**
	 * 할인 코드 사용 횟수를 하나 되돌린다. 무료 이벤트 표기 같은 시스템 표기는 discount_codes 에 없는 값이라, 부르는 쪽
	 * (OrderDiscountRestorer)이 {@code Order.hasSystemDiscountCode()} 로 걸러 넘기지 않는다.
	 */
	@Transactional
	public void restoreDiscountUsage(String code) {
		if (code == null || code.isBlank()) {
			return;
		}

		DiscountCode discountCode = discountCodeRepository.findByCodeForUpdate(code)
			.orElseThrow(() -> new PaymentException("존재하지 않는 할인 코드입니다."));

		discountCode.decreaseUsage();
	}

	private void validateSubCategory(DiscountCode discountCode, Long subCategoryId) {
		if (discountCode.getSubCategoryId() != null &&
			!discountCode.getSubCategoryId().equals(subCategoryId)) {
			throw new PaymentException("해당 상품에는 적용할 수 없는 할인 코드입니다.");
		}
		if (discountCode.getCategoryId() != null) {
			SubCategory subCategory = subCategoryRepository.findById(subCategoryId)
				.orElseThrow(() -> new PaymentException("상품을 찾을 수 없습니다."));
			if (!discountCode.getCategoryId().equals(subCategory.getCategoryId())) {
				throw new PaymentException("해당 상품에는 적용할 수 없는 할인 코드입니다.");
			}
		}
	}

	@Getter
	@RequiredArgsConstructor
	public static class DiscountValidationResult {

		private final int finalAmount;
		private final String appliedCode;
		private final DiscountCode discountCodeEntity; // 횟수 증가를 위해 엔티티 자체를 전달
	}
}
