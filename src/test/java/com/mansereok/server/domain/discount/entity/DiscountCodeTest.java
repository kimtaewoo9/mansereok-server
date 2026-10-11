package com.mansereok.server.domain.discount.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.fixture.DiscountCodeFixture;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 할인 코드가 지금 지키는 규칙(금액 계산, 사용 가능 여부, 사용 횟수)을 고정한다.
 */
class DiscountCodeTest {

	// 코드 만료를 판정할 지금 시각. 빌더의 기본 만료 시각(실제 지금부터 30일 뒤)보다 앞선 날로 둔다.
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 1, 1, 12, 0);

	@Nested
	@DisplayName("할인 금액을 계산할 때")
	class ApplyDiscount {

		@ParameterizedTest(name = "[{index}] {0} {1} 을 {2}원에 적용하면 {3}원")
		@DisplayName("할인액은 원 단위로 버리고, 결과는 10원 단위로 버리며, 100% 할인이 아니면 1,000원 아래로 내려가지 않는다")
		@CsvSource(textBlock = """
			# 할인 종류,   할인 값, 원래 금액, 결제 금액
			FIXED_AMOUNT,  3000,  10000,  7000
			PERCENTAGE,      15,  10000,  8500
			# 할인액 999.9원은 999원으로 버린 뒤, 9000원에서 10원 단위 버림
			PERCENTAGE,      10,   9999,  9000
			# 9005원은 10원 단위로 버려 9000원
			FIXED_AMOUNT,  1000,  10005,  9000
			# 500원이 되지만 최저 결제 금액 1000원
			FIXED_AMOUNT,  9500,  10000,  1000
			# 100% 할인만 0원을 허용한다
			PERCENTAGE,     100,  10000,     0
			""")
		void appliesDiscountRules(DiscountType discountType, int discountValue, int originalAmount,
			int expectedAmount) {
			// given
			DiscountCode discountCode = DiscountCodeFixture.usableCode()
				.discountType(discountType)
				.discountValue(discountValue)
				.build();

			// when
			int finalAmount = discountCode.applyDiscount(originalAmount);

			// then
			assertThat(finalAmount).isEqualTo(expectedAmount);
		}

		@Test
		@DisplayName("주문 금액이 최소 주문 금액과 같으면 할인한다")
		void appliesWhenAmountEqualsMinimum() {
			// given
			DiscountCode discountCode = DiscountCodeFixture.fixedAmount(1000).minPurchaseAmount(20000).build();

			// when
			int finalAmount = discountCode.applyDiscount(20000);

			// then
			assertThat(finalAmount).isEqualTo(19000);
		}

		@Test
		@DisplayName("주문 금액이 최소 주문 금액보다 1원이라도 적으면 최소 금액을 알려 주며 거절한다")
		void rejectsAmountBelowMinimum() {
			// given
			DiscountCode discountCode = DiscountCodeFixture.fixedAmount(1000).minPurchaseAmount(20000).build();

			// when & then
			assertThatThrownBy(() -> discountCode.applyDiscount(19999))
				.isInstanceOf(PaymentException.class)
				.hasMessage("최소 주문 금액(20000원)을 충족하지 못했습니다.");
		}
	}

	@Nested
	@DisplayName("사용할 수 있는 코드인지 확인할 때")
	class Validate {

		@Test
		@DisplayName("활성이고 만료 전이며 사용 횟수가 남았으면 통과한다")
		void passesUsableCode() {
			// given: 최대 5번 중 4번 사용
			DiscountCode discountCode = DiscountCodeFixture.usableCode().maxUses(5).currentUses(4).build();

			// when & then
			assertThatCode(() -> discountCode.validate(NOW)).doesNotThrowAnyException();
		}

		@Test
		@DisplayName("비활성 코드는 거절한다")
		void rejectsInactiveCode() {
			// given
			DiscountCode discountCode = DiscountCodeFixture.usableCode().active(false).build();

			// when & then
			assertThatThrownBy(() -> discountCode.validate(NOW))
				.isInstanceOf(PaymentException.class)
				.hasMessage("비활성화된 코드입니다.");
		}

		@Test
		@DisplayName("만료 시각이 지난 코드는 거절한다")
		void rejectsExpiredCode() {
			// given
			DiscountCode discountCode = DiscountCodeFixture.usableCode()
				.expiresAt(NOW.minusDays(1))
				.build();

			// when & then
			assertThatThrownBy(() -> discountCode.validate(NOW))
				.isInstanceOf(PaymentException.class)
				.hasMessage("기간이 만료된 코드입니다.");
		}

		@Test
		@DisplayName("사용 횟수가 최대 횟수에 닿은 코드는 선착순 마감으로 거절한다")
		void rejectsUsedUpCode() {
			// given: 최대 5번 중 5번 사용
			DiscountCode discountCode = DiscountCodeFixture.usableCode().maxUses(5).currentUses(5).build();

			// when & then
			assertThatThrownBy(() -> discountCode.validate(NOW))
				.isInstanceOf(PaymentException.class)
				.hasMessage("선착순 마감된 코드입니다.");
		}
	}

	@Nested
	@DisplayName("사용 횟수를 바꿀 때")
	class UsageCount {

		@Test
		@DisplayName("최대 횟수 아래면 사용 횟수를 1 올린다")
		void incrementsBelowLimit() {
			// given
			DiscountCode discountCode = DiscountCodeFixture.usableCode().maxUses(5).currentUses(4).build();

			// when
			discountCode.incrementUsage();

			// then
			assertThat(discountCode.getCurrentUses()).isEqualTo(5);
		}

		@Test
		@DisplayName("이미 최대 횟수면 더 올리지 않고 거절한다")
		void rejectsIncrementAtLimit() {
			// given
			DiscountCode discountCode = DiscountCodeFixture.usableCode().maxUses(5).currentUses(5).build();

			// when & then
			assertThatThrownBy(discountCode::incrementUsage)
				.isInstanceOf(PaymentException.class)
				.hasMessage("할인 코드 사용 횟수가 초과되었습니다.");
			assertThat(discountCode.getCurrentUses()).isEqualTo(5);
		}

		@ParameterizedTest(name = "[{index}] 최대 {0}번 중 {1}번 사용 → 올림 {2}, 결과 {3}번")
		@DisplayName("늦은 결제 몫으로 올릴 때는 최대 횟수 안일 때만 1 올리고, 닿았으면 그대로 두고 false 를 돌려준다")
		@CsvSource(textBlock = """
			# 최대 횟수, 사용 횟수, 올렸는가, 올린 뒤 사용 횟수
			5, 4, true,  5
			5, 5, false, 5
			""")
		void incrementsForLatePaymentOnlyWithinLimit(int maxUses, int currentUses, boolean expectedClaimed,
			int expectedUses) {
			// given
			DiscountCode discountCode = DiscountCodeFixture.usableCode().maxUses(maxUses).currentUses(currentUses)
				.build();

			// when
			boolean claimed = discountCode.incrementUsageForLatePayment();

			// then
			assertThat(claimed).isEqualTo(expectedClaimed);
			assertThat(discountCode.getCurrentUses()).isEqualTo(expectedUses);
		}

		@Test
		@DisplayName("사용 횟수를 되돌리면 1 내린다")
		void decrementsUsage() {
			// given
			DiscountCode discountCode = DiscountCodeFixture.usableCode().currentUses(1).build();

			// when
			discountCode.decreaseUsage();

			// then
			assertThat(discountCode.getCurrentUses()).isZero();
		}

		@Test
		@DisplayName("사용 횟수가 0 이면 되돌려도 0 아래로 내려가지 않는다")
		void doesNotGoBelowZero() {
			// given
			DiscountCode discountCode = DiscountCodeFixture.usableCode().currentUses(0).build();

			// when
			discountCode.decreaseUsage();

			// then
			assertThat(discountCode.getCurrentUses()).isZero();
		}
	}
}
