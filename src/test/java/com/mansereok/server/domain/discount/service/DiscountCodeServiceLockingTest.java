package com.mansereok.server.domain.discount.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.mansereok.server.domain.discount.entity.DiscountCode;
import com.mansereok.server.domain.discount.repository.DiscountCodeRepository;
import com.mansereok.server.domain.discount.service.DiscountCodeService.DiscountValidationResult;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.fixture.DiscountCodeFixture;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 할인 코드 사용 횟수를 읽고 바꾸는 세 메서드가 행을 잠가(findByCodeForUpdate) 읽는지와, 만료 뒤 결제된 주문 몫으로 사용 횟수를
 * 다시 올리는 규칙을 확인한다.
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

	@Nested
	@DisplayName("만료 뒤 결제된 주문 몫으로 사용 횟수를 다시 올릴 때(claimForPaidOrder)")
	class ClaimForPaidOrder {

		@Test
		@DisplayName("최대 횟수 안이면 1 올리고 true 를 돌려준다")
		void incrementsWithinLimit() {
			// given: 최대 5번 중 4번 사용
			DiscountCode discountCode = DiscountCodeFixture.usableCode().code(CODE).maxUses(5).currentUses(4)
				.build();
			givenLockedLookupReturns(discountCode);

			// when
			boolean claimed = discountCodeService.claimForPaidOrder(CODE);

			// then
			assertThat(claimed).isTrue();
			assertThat(discountCode.getCurrentUses()).isEqualTo(5);
		}

		@Test
		@DisplayName("이미 최대 횟수면 올리지 않고 false 를 돌려준다. 호출자가 그 결제를 취소한다")
		void keepsCountAtLimit() {
			// given: 최대 5번 중 5번 사용
			DiscountCode discountCode = DiscountCodeFixture.usableCode().code(CODE).maxUses(5).currentUses(5)
				.build();
			givenLockedLookupReturns(discountCode);

			// when
			boolean claimed = discountCodeService.claimForPaidOrder(CODE);

			// then
			assertThat(claimed).isFalse();
			assertThat(discountCode.getCurrentUses()).isEqualTo(5);
		}

		@Test
		@DisplayName("코드가 비활성이거나 기간이 지났어도 주문을 만들 때 이미 확인했으므로 올린다")
		void incrementsInactiveExpiredCode() {
			// given
			DiscountCode discountCode = DiscountCodeFixture.usableCode().code(CODE).active(false)
				.expiresAt(LocalDateTime.now().minusDays(1)).currentUses(0).build();
			givenLockedLookupReturns(discountCode);

			// when
			boolean claimed = discountCodeService.claimForPaidOrder(CODE);

			// then
			assertThat(claimed).isTrue();
			assertThat(discountCode.getCurrentUses()).isEqualTo(1);
		}

		@Test
		@DisplayName("코드가 없으면 '존재하지 않는 할인 코드입니다.' 로 거절한다")
		void rejectsMissingCode() {
			// given
			given(discountCodeRepository.findByCodeForUpdate(CODE)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> discountCodeService.claimForPaidOrder(CODE))
				.isInstanceOf(PaymentException.class)
				.hasMessage("존재하지 않는 할인 코드입니다.");
		}
	}
}
