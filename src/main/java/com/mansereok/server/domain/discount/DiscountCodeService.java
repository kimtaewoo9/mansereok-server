package com.mansereok.server.domain.discount;

import com.mansereok.server.domain.discount.dto.request.DiscountCheckRequest;
import com.mansereok.server.domain.discount.dto.response.DiscountCheckResponse;
import com.mansereok.server.domain.discount.entity.DiscountCode;
import com.mansereok.server.domain.discount.repository.DiscountCodeRepository;
import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.global.exception.PaymentException;
import jakarta.persistence.EntityNotFoundException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DiscountCodeService {

	private final DiscountCodeRepository discountCodeRepository;
	private final SubCategoryRepository subCategoryRepository;

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

		discountCode.validate();

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

		// 2. [핵심] 락을 거는 findByCode 사용
		DiscountCode discountCode = discountCodeRepository.findByCode(code)
			.orElseThrow(() -> new PaymentException("유효하지 않은 코드입니다."));

		// 3. 최종 검증 (락이 걸린 상태에서)
		discountCode.validate();

		// 3_1. 특정 상품에만 쓸 수 있는 코드인지 여기서도 검증 .
		validateSubCategory(discountCode, subCategoryId);

		// 4. 최종 할인액 계산
		int finalAmount = discountCode.applyDiscount(originalAmount);

		// 5. 락이 걸린 엔티티와 최종 금액, 코드명을 반환
		return new DiscountValidationResult(finalAmount, code, discountCode);
	}

	public void incrementUsage(DiscountCode discountCode) {
		if (discountCode == null) {
			return;
		}

		// 락이 걸린 엔티티의 횟수 증가
		discountCode.incrementUsage();
		discountCodeRepository.save(discountCode); // 변경 감지(Dirty checking)
	}

	private void validateSubCategory(DiscountCode discountCode, Long subCategoryId) {
		if (discountCode.getSubCategoryId() != null &&
			!discountCode.getSubCategoryId().equals(subCategoryId)) {
			throw new PaymentException("이 상품에는 적용할 수 없는 할인 코드입니다.");
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
