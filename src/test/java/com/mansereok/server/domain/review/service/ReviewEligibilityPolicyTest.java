package com.mansereok.server.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.support.fixture.OrderFixture;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 리뷰 작성 자격 규칙(소유자, 상품, 결제, 30일, 중복)을 규칙마다 한 가지만 어긋난 주문으로 확인한다.
 *
 * <p>주문 기본값은 회원 10 이 상품 3 을 2026-09-10 에 결제한 PAID 주문이라 모든 규칙을 통과한다. 표의 각 줄은 그중 한 값만 바꾼다.
 * 오늘은 2026-09-25 로 고정한다. 결제한 날을 0일로 세므로 2026-08-26 결제는 30일째(허용), 2026-08-25 결제는 31일째(거절)다.
 */
class ReviewEligibilityPolicyTest {

	private static final long REQUESTER_ID = 10L;
	private static final long PRODUCT_ID = 3L;
	private static final LocalDate TODAY = LocalDate.of(2026, 9, 25);

	@Nested
	@DisplayName("중복 확인 앞의 규칙에 걸리는 주문이면")
	class WhenEarlierRuleFails {

		static Stream<Arguments> ordersRejectedBeforeDuplicateCheck() {
			return Stream.of(
				Arguments.of("남의 주문", OrderFixture.paidOrder().userId(11L).build(), RejectionReason.NOT_OWNER),
				Arguments.of("탈퇴한 회원의 주문(user_id NULL)", OrderFixture.paidOrder().userId(null).build(),
					RejectionReason.NOT_OWNER),
				Arguments.of("다른 상품의 주문", OrderFixture.paidOrder().subCategoryId(4L).build(),
					RejectionReason.MISMATCH_PRODUCT),
				Arguments.of("결제 대기(PENDING)",
					OrderFixture.paidOrder().status(OrderStatus.PENDING).paidAt(null).build(),
					RejectionReason.NOT_PAID),
				Arguments.of("가상계좌 입금 대기(VIRTUAL_ACCOUNT_ISSUED)",
					OrderFixture.paidOrder().status(OrderStatus.VIRTUAL_ACCOUNT_ISSUED).paidAt(null).build(),
					RejectionReason.NOT_PAID),
				Arguments.of("환불(CANCELLED, 결제 시각은 남아 있음)",
					OrderFixture.paidOrder().status(OrderStatus.CANCELLED).build(), RejectionReason.NOT_PAID),
				Arguments.of("결제 실패(FAILED)",
					OrderFixture.paidOrder().status(OrderStatus.FAILED).paidAt(null).build(),
					RejectionReason.NOT_PAID),
				Arguments.of("주문 만료(EXPIRED)",
					OrderFixture.paidOrder().status(OrderStatus.EXPIRED).paidAt(null).build(),
					RejectionReason.NOT_PAID),
				Arguments.of("PAID 인데 결제 시각이 비어 있음", OrderFixture.paidOrder().paidAt(null).build(),
					RejectionReason.NOT_PAID),
				Arguments.of("결제 후 31일째(2026-08-25 23:59 결제)",
					OrderFixture.paidOrder().paidAt(LocalDateTime.of(2026, 8, 25, 23, 59)).build(),
					RejectionReason.EXPIRED)
			);
		}

		@ParameterizedTest(name = "[{index}] {0} → {2}")
		@MethodSource("ordersRejectedBeforeDuplicateCheck")
		@DisplayName("그 규칙의 이유를 돌려주고 이미 쓴 리뷰가 있는지는 묻지 않는다")
		void rejectsWithoutAskingForExistingReview(String situation, Order order, RejectionReason expected) {
			// given: 이미 쓴 리뷰가 있다고 답하는 조회. 중복 확인을 앞 규칙보다 먼저 하면 ALREADY_WRITTEN 이 나온다.
			AtomicInteger existingReviewLookups = new AtomicInteger();
			BooleanSupplier alreadyWritten = () -> {
				existingReviewLookups.incrementAndGet();
				return true;
			};

			// when
			Optional<RejectionReason> rejection = ReviewEligibilityPolicy.findRejection(order, REQUESTER_ID,
				PRODUCT_ID, TODAY, alreadyWritten);

			// then
			assertThat(rejection).contains(expected);
			assertThat(existingReviewLookups).as("앞 규칙에서 멈추면 DB 조회를 하지 않는다").hasValue(0);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(value = OrderStatus.class, mode = Mode.EXCLUDE, names = "PAID")
		@DisplayName("PAID 가 아닌 주문은 결제 시각이 남아 있어도 NOT_PAID 다")
		void rejectsEveryStatusOtherThanPaid(OrderStatus status) {
			// given: 결제 시각은 기한 안이라 결제 상태만 규칙에 어긋난다
			Order order = OrderFixture.paidOrder().status(status).build();

			// when
			Optional<RejectionReason> rejection = ReviewEligibilityPolicy.findRejection(order, REQUESTER_ID,
				PRODUCT_ID, TODAY, () -> false);

			// then
			assertThat(rejection).contains(RejectionReason.NOT_PAID);
		}
	}

	@Nested
	@DisplayName("앞 규칙을 모두 통과하면")
	class WhenEarlierRulesPass {

		@ParameterizedTest(name = "[{index}] {0} 결제 → 쓸 수 있음")
		@CsvSource({
			"2026-09-25T00:00, 결제 당일",
			"2026-08-26T23:59, 결제 후 30일째",
		})
		@DisplayName("이 주문으로 쓴 리뷰가 없으면 쓸 수 있다")
		void allowsWhenNoReviewYet(LocalDateTime paidAt, String situation) {
			// given
			Order order = OrderFixture.paidOrder().paidAt(paidAt).build();

			// when
			Optional<RejectionReason> rejection = ReviewEligibilityPolicy.findRejection(order, REQUESTER_ID,
				PRODUCT_ID, TODAY, () -> false);

			// then
			assertThat(rejection).isEmpty();
		}

		@Test
		@DisplayName("이 주문으로 쓴 리뷰가 이미 있으면 ALREADY_WRITTEN 이다")
		void rejectsWhenReviewAlreadyWritten() {
			// given
			Order order = OrderFixture.paidOrder().build();

			// when
			Optional<RejectionReason> rejection = ReviewEligibilityPolicy.findRejection(order, REQUESTER_ID,
				PRODUCT_ID, TODAY, () -> true);

			// then
			assertThat(rejection).contains(RejectionReason.ALREADY_WRITTEN);
		}
	}
}
