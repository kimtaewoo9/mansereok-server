package com.mansereok.server.domain.coupon.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.global.exception.CouponSoldOutException;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.fixture.CouponTemplateFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 선착순 쿠폰 템플릿의 마감 판정과 발급 수 올리기를 확인한다.
 *
 * <p>마감 판정(isSoldOut)은 쿠폰 받기와 이벤트 목록의 마감 표시가 함께 쓰는 한 곳이다. 마감은 사람이 몰릴 때 늦게 온 요청 대부분이
 * 겪는 정상적인 거절이라 서버 오류(IllegalStateException, 500)가 아니라 결제 예외 계열(400)로 던져야 한다.
 */
class CouponTemplateTest {

	@ParameterizedTest(name = "[{index}] 상한 {0}, 발급 수 {1} → 마감 {2}")
	@CsvSource(textBlock = """
		# 선착순 상한(빈 칸은 무제한), 발급 수, 마감 여부
		    ,    0, false
		    , 1000, false
		 100,    0, false
		 100,   99, false
		 100,  100, true
		# 손으로 고친 데이터 등으로 이미 넘친 경우도 마감이다
		 100,  101, true
		   0,    0, true
		""")
	@DisplayName("발급 수가 선착순 상한에 닿으면 마감이고, 상한이 없으면 발급 수와 관계없이 마감이 아니다")
	void soldOutWhenIssueCountReachesMax(Integer maxIssueCount, int currentIssueCount, boolean expected) {
		// given
		CouponTemplate template = CouponTemplateFixture.issuableNow()
			.maxIssueCount(maxIssueCount).currentIssueCount(currentIssueCount).build();

		// when
		boolean soldOut = template.isSoldOut();

		// then
		assertThat(soldOut).isEqualTo(expected);
	}

	@Nested
	@DisplayName("발급 수를 올릴 때(incrementIssueCount)")
	class IncrementIssueCount {

		@Test
		@DisplayName("상한보다 하나 적게 발급했으면 발급 수를 1 올려 상한에 닿는다")
		void incrementsUpToMax() {
			// given
			CouponTemplate template = CouponTemplateFixture.issuableNow()
				.maxIssueCount(100).currentIssueCount(99).build();

			// when
			template.incrementIssueCount();

			// then
			assertThat(template.getCurrentIssueCount()).isEqualTo(100);
			assertThat(template.isSoldOut()).isTrue();
		}

		@Test
		@DisplayName("상한이 없으면 몇 장을 발급했든 발급 수를 1 올린다")
		void incrementsWithoutMax() {
			// given
			CouponTemplate template = CouponTemplateFixture.issuableNow()
				.maxIssueCount(null).currentIssueCount(1000).build();

			// when
			template.incrementIssueCount();

			// then
			assertThat(template.getCurrentIssueCount()).isEqualTo(1001);
		}

		@Test
		@DisplayName("이미 상한만큼 발급했으면 결제 예외 계열인 CouponSoldOutException('선착순 마감되었습니다.')을 던지고 발급 수는 그대로 둔다")
		void rejectsWhenSoldOut() {
			// given
			CouponTemplate template = CouponTemplateFixture.issuableNow()
				.maxIssueCount(100).currentIssueCount(100).build();

			// when & then
			assertThatThrownBy(template::incrementIssueCount)
				.isInstanceOf(CouponSoldOutException.class)
				.isInstanceOf(PaymentException.class)
				.hasMessage("선착순 마감되었습니다.");
			assertThat(template.getCurrentIssueCount()).isEqualTo(100);
		}
	}
}
