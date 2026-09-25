package com.mansereok.server.domain.discount.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.mansereok.server.domain.discount.entity.DiscountCode;
import com.mansereok.server.domain.discount.repository.DiscountCodeRepository;
import com.mansereok.server.domain.discount.service.DiscountCodeService.DiscountValidationResult;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.support.fixture.DiscountCodeFixture;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 할인 코드 사용 횟수를 읽고 바꾸는 메서드가 행을 잠가(findByCodeForUpdate) 읽는지 확인한다.
 *
 * <p>잠금 자체는 DB 가 지키므로 여기서는 "잠금 조회로 읽는다" 까지만 본다. 리포지토리 목은 findByCodeForUpdate 만 스텁한다.
 * 코드가 다른 조회로 읽으면 스텁되지 않은 조회가 빈 값을 돌려줘 "코드 없음" 으로 실패하고, strict stubs 가 쓰이지 않은 스텁을 알린다.
 */
@ExtendWith(MockitoExtension.class)
class DiscountCodeServiceLockingTest {

	private static final String CODE = "SALE10";

	@Mock
	private DiscountCodeRepository discountCodeRepository;
	@Mock
	private SubCategoryRepository subCategoryRepository;

	private DiscountCodeService discountCodeService;

	@BeforeEach
	void setUp() {
		discountCodeService = new DiscountCodeService(discountCodeRepository, subCategoryRepository);
	}

	private void givenLockedLookupReturns(DiscountCode discountCode) {
		given(discountCodeRepository.findByCodeForUpdate(CODE)).willReturn(Optional.of(discountCode));
	}

	@Test
	@DisplayName("주문을 만들며 코드를 검증할 때 행을 잠가 읽고 할인가를 돌려준다")
	void validateForPaymentReadsCodeWithLock() {
		// given
		DiscountCode discountCode = DiscountCodeFixture.fixedAmount(3000).code(CODE).build();
		givenLockedLookupReturns(discountCode);

		// when
		DiscountValidationResult result = discountCodeService.validateAndCalculateDiscountForPayment(CODE,
			10000, 1L);

		// then
		assertThat(result.getFinalAmount()).isEqualTo(7000);
		assertThat(result.getDiscountCodeEntity()).isSameAs(discountCode);
	}

	@Test
	@DisplayName("사용 횟수를 되돌릴 때 행을 잠가 읽고 1 내린다")
	void restoreReadsCodeWithLock() {
		// given
		DiscountCode discountCode = DiscountCodeFixture.usableCode().code(CODE).currentUses(3).build();
		givenLockedLookupReturns(discountCode);

		// when
		discountCodeService.restoreDiscountUsage(CODE);

		// then
		assertThat(discountCode.getCurrentUses()).isEqualTo(2);
	}
}
