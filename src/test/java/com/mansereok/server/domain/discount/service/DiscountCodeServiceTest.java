package com.mansereok.server.domain.discount.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.discount.entity.DiscountCode;
import com.mansereok.server.domain.discount.repository.DiscountCodeRepository;
import com.mansereok.server.domain.discount.service.DiscountCodeService.DiscountValidationResult;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.fixture.DiscountCodeFixture;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 주문에 할인 코드를 쓸 때의 상품·카테고리 제한과 없는 코드 거절, 코드를 넣지 않은 주문을 확인한다.
 *
 * <p>리포지토리 목은 돌려줄 할인 코드와 상품만 정한다. 할인 코드와 상품은 목이 아니라 빌더로 만든 진짜 엔티티다.
 *
 * <p>EVENT_FREE(무료 이벤트 주문에 적는 표기)는 여기서 보지 않는다. 할인을 되돌리는 쪽은 OrderDiscountRestorer 가 EVENT_FREE 를
 * 먼저 걸러 이 서비스를 부르지 않으며, 그 규칙은 OrderDiscountRestorerTest 가 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class DiscountCodeServiceTest {

	private static final String CODE = "SALE10";
	private static final int ORIGINAL_AMOUNT = 10000;
	private static final Long PRODUCT_ID = 3L;
	private static final Long OTHER_PRODUCT_ID = 5L;
	// SubCategoryFixture 로 만든 상품의 카테고리(기본값 1)
	private static final Long PRODUCT_CATEGORY_ID = 1L;
	private static final Long OTHER_CATEGORY_ID = 2L;

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

	private void givenProductExists() {
		given(subCategoryRepository.findById(PRODUCT_ID))
			.willReturn(Optional.of(SubCategoryFixture.paidProduct().id(PRODUCT_ID).build()));
	}

	@Nested
	@DisplayName("주문에 쓸 할인 코드를 검증할 때(validateAndCalculateDiscountForPayment)")
	class ValidateForPayment {

		@Nested
		@DisplayName("특정 상품 전용 코드면")
		class WhenCodeIsLimitedToProduct {

			@Test
			@DisplayName("그 상품 주문에는 할인가를 돌려준다")
			void appliesToThatProduct() {
				// given
				givenLockedLookupReturns(DiscountCodeFixture.fixedAmount(3000).code(CODE)
					.subCategoryId(PRODUCT_ID).build());

				// when
				DiscountValidationResult result = discountCodeService.validateAndCalculateDiscountForPayment(CODE,
					ORIGINAL_AMOUNT, PRODUCT_ID);

				// then
				assertThat(result.getFinalAmount()).isEqualTo(7000);
			}

			@Test
			@DisplayName("다른 상품 주문이면 '해당 상품에는 적용할 수 없는 할인 코드입니다.' 로 거절한다")
			void rejectsOtherProduct() {
				// given
				givenLockedLookupReturns(DiscountCodeFixture.fixedAmount(3000).code(CODE)
					.subCategoryId(OTHER_PRODUCT_ID).build());

				// when & then
				assertThatThrownBy(() -> discountCodeService.validateAndCalculateDiscountForPayment(CODE,
					ORIGINAL_AMOUNT, PRODUCT_ID))
					.isInstanceOf(PaymentException.class)
					.hasMessage("해당 상품에는 적용할 수 없는 할인 코드입니다.");
			}
		}

		@Nested
		@DisplayName("특정 카테고리 전용 코드면")
		class WhenCodeIsLimitedToCategory {

			@Test
			@DisplayName("그 카테고리에 속한 상품 주문에는 할인가를 돌려준다")
			void appliesToProductInThatCategory() {
				// given
				givenLockedLookupReturns(DiscountCodeFixture.fixedAmount(3000).code(CODE)
					.categoryId(PRODUCT_CATEGORY_ID).build());
				givenProductExists();

				// when
				DiscountValidationResult result = discountCodeService.validateAndCalculateDiscountForPayment(CODE,
					ORIGINAL_AMOUNT, PRODUCT_ID);

				// then
				assertThat(result.getFinalAmount()).isEqualTo(7000);
			}

			@Test
			@DisplayName("다른 카테고리 상품 주문이면 '해당 상품에는 적용할 수 없는 할인 코드입니다.' 로 거절한다")
			void rejectsProductInOtherCategory() {
				// given
				givenLockedLookupReturns(DiscountCodeFixture.fixedAmount(3000).code(CODE)
					.categoryId(OTHER_CATEGORY_ID).build());
				givenProductExists();

				// when & then
				assertThatThrownBy(() -> discountCodeService.validateAndCalculateDiscountForPayment(CODE,
					ORIGINAL_AMOUNT, PRODUCT_ID))
					.isInstanceOf(PaymentException.class)
					.hasMessage("해당 상품에는 적용할 수 없는 할인 코드입니다.");
			}
		}

		@Test
		@DisplayName("없는 코드면 '유효하지 않은 코드입니다.' 로 거절한다")
		void rejectsUnknownCode() {
			// given
			given(discountCodeRepository.findByCodeForUpdate(CODE)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> discountCodeService.validateAndCalculateDiscountForPayment(CODE,
				ORIGINAL_AMOUNT, PRODUCT_ID))
				.isInstanceOf(PaymentException.class)
				.hasMessage("유효하지 않은 코드입니다.");
		}

		@ParameterizedTest(name = "[{index}] 할인 코드 \"{0}\"")
		@NullAndEmptySource
		@ValueSource(strings = {"   "})
		@DisplayName("코드를 넣지 않았거나 공백이면 코드를 찾지 않고 원가 그대로, 적용한 코드 없이 돌려준다")
		void returnsOriginalAmountWithoutCode(String code) {
			// when
			DiscountValidationResult result = discountCodeService.validateAndCalculateDiscountForPayment(code,
				ORIGINAL_AMOUNT, PRODUCT_ID);

			// then
			assertThat(result.getFinalAmount()).isEqualTo(ORIGINAL_AMOUNT);
			assertThat(result.getAppliedCode()).isNull();
			assertThat(result.getDiscountCodeEntity()).isNull();
		}
	}

	@Nested
	@DisplayName("사용 횟수를 되돌릴 때(restoreDiscountUsage)")
	class RestoreDiscountUsage {

		@ParameterizedTest(name = "[{index}] 할인 코드 \"{0}\"")
		@NullAndEmptySource
		@ValueSource(strings = {"   "})
		@DisplayName("코드가 없거나 공백이면 코드를 조회하지 않고 그대로 끝난다")
		void doesNothingWithoutCode(String code) {
			// when & then
			assertThatCode(() -> discountCodeService.restoreDiscountUsage(code)).doesNotThrowAnyException();
			verifyNoInteractions(discountCodeRepository);
		}
	}
}
