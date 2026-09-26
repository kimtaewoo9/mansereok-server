package com.mansereok.server.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.review.dto.request.ReviewCreateRequest;
import com.mansereok.server.domain.review.dto.response.ReviewEligibilityResponse;
import com.mansereok.server.domain.review.entity.Review;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.ReviewNotAllowedException;
import com.mansereok.server.support.fixture.OrderFixture;
import com.mansereok.server.support.fixture.UserFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 자격 조회 API(checkReviewEligibility)와 작성 API(createReview)가 같은 주문에 같은 이유로 답하는지 한 표로 확인한다.
 *
 * <p>지금은 한국 시각 2026-09-25 00:00 으로 고정한다. 결제한 날을 0일로 세므로 2026-08-26 23:59 결제는 30일째(허용),
 * 2026-08-25 23:59 결제는 31일째(거절)다. 서비스가 주입받은 Clock 이 아니라 시스템 시계나 다른 시간대로 오늘을 구하면 이 경계
 * 줄이 깨진다.
 *
 * <p>회원 조회는 진짜 {@link UserService} 가 한다. 목으로 두는 것은 저장소(돌려줄 값만 정한다)뿐이다.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceEligibilityTest {

	private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-24T15:00:00Z"), ZoneId.of("Asia/Seoul"));
	private static final long WRITER_ID = 10L;
	private static final long ORDER_ID = 100L;
	private static final long PRODUCT_ID = 3L;
	private static final String CONTENT = "풀이가 자세하고 이해하기 쉬워서 많은 도움이 되었습니다.";

	@Mock
	private ReviewRepository reviewRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private UserRepository userRepository;

	// 생성자 주입. 이 테스트가 목으로 두지 않은 UserService 의 협력 객체는 null 로 들어가며, 회원 조회에서는 쓰이지 않는다.
	@InjectMocks
	private UserService userService;

	private ReviewService reviewService;

	@BeforeEach
	void setUp() {
		reviewService = new ReviewService(reviewRepository, orderRepository, userService, NOW);
		User writer = User.create("writer", "홍길동", "password", "writer@example.com", LocalDate.of(1990, 1, 1),
			Gender.MALE, true, true, false);
		UserFixture.withId(writer, WRITER_ID);
		given(userRepository.findByUsername("writer")).willReturn(Optional.of(writer));
	}

	static Stream<Arguments> rejectedOrders() {
		return Stream.of(
			Arguments.of("주문 없음", null, false, RejectionReason.ORDER_NOT_FOUND),
			Arguments.of("남의 주문", OrderFixture.paidOrder().userId(11L).build(), false,
				RejectionReason.NOT_OWNER),
			Arguments.of("탈퇴한 회원의 주문(user_id NULL)", OrderFixture.paidOrder().userId(null).build(), false,
				RejectionReason.NOT_OWNER),
			Arguments.of("다른 상품의 주문", OrderFixture.paidOrder().subCategoryId(4L).build(), false,
				RejectionReason.MISMATCH_PRODUCT),
			Arguments.of("결제 대기(PENDING)",
				OrderFixture.paidOrder().status(OrderStatus.PENDING).paidAt(null).build(), false,
				RejectionReason.NOT_PAID),
			Arguments.of("가상계좌 입금 대기(VIRTUAL_ACCOUNT_ISSUED)",
				OrderFixture.paidOrder().status(OrderStatus.VIRTUAL_ACCOUNT_ISSUED).paidAt(null).build(), false,
				RejectionReason.NOT_PAID),
			Arguments.of("환불(CANCELLED, 결제 시각은 남아 있음)",
				OrderFixture.paidOrder().status(OrderStatus.CANCELLED).build(), false, RejectionReason.NOT_PAID),
			Arguments.of("결제 실패(FAILED)",
				OrderFixture.paidOrder().status(OrderStatus.FAILED).paidAt(null).build(), false,
				RejectionReason.NOT_PAID),
			Arguments.of("주문 만료(EXPIRED)",
				OrderFixture.paidOrder().status(OrderStatus.EXPIRED).paidAt(null).build(), false,
				RejectionReason.NOT_PAID),
			Arguments.of("결제 후 31일째(2026-08-25 23:59 결제)",
				OrderFixture.paidOrder().paidAt(LocalDateTime.of(2026, 8, 25, 23, 59)).build(), false,
				RejectionReason.EXPIRED),
			Arguments.of("이미 리뷰를 쓴 주문", OrderFixture.paidOrder().build(), true,
				RejectionReason.ALREADY_WRITTEN)
		);
	}

	static Stream<Arguments> allowedOrders() {
		return Stream.of(
			Arguments.of("결제 당일(2026-09-25 00:00 결제)",
				OrderFixture.paidOrder().paidAt(LocalDateTime.of(2026, 9, 25, 0, 0)).build()),
			Arguments.of("결제 후 30일째(2026-08-26 23:59 결제)",
				OrderFixture.paidOrder().paidAt(LocalDateTime.of(2026, 8, 26, 23, 59)).build())
		);
	}

	@Nested
	@DisplayName("리뷰를 쓸 수 없는 주문이면")
	class WhenOrderIsRejected {

		@ParameterizedTest(name = "[{index}] {0} → {3}")
		@MethodSource("com.mansereok.server.domain.review.service.ReviewServiceEligibilityTest#rejectedOrders")
		@DisplayName("자격 조회는 그 이유와 문구를 담아 답한다")
		void eligibilityCheckAnswersReason(String situation, Order order, boolean alreadyWritten,
			RejectionReason expected) {
			// given
			givenOrderLookup(order, alreadyWritten);

			// when
			ReviewEligibilityResponse response = reviewService.checkReviewEligibility("writer", ORDER_ID,
				PRODUCT_ID);

			// then
			assertThat(response).isEqualTo(new ReviewEligibilityResponse(false, expected, expected.getMessage()));
		}

		@ParameterizedTest(name = "[{index}] {0} → {3}")
		@MethodSource("com.mansereok.server.domain.review.service.ReviewServiceEligibilityTest#rejectedOrders")
		@DisplayName("작성은 자격 조회와 같은 이유의 ReviewNotAllowedException 으로 거절하고 리뷰를 저장하지 않는다")
		void createReviewRejectsWithSameReason(String situation, Order order, boolean alreadyWritten,
			RejectionReason expected) {
			// given
			givenOrderLookup(order, alreadyWritten);

			// when & then
			assertThatThrownBy(() -> reviewService.createReview("writer", request()))
				.isInstanceOfSatisfying(ReviewNotAllowedException.class,
					e -> assertThat(e.getReason()).isEqualTo(expected))
				.hasMessage(expected.getMessage());
			then(reviewRepository).should(never()).save(any());
		}
	}

	@Nested
	@DisplayName("리뷰를 쓸 수 있는 주문이면")
	class WhenOrderIsAllowed {

		@ParameterizedTest(name = "[{index}] {0}")
		@MethodSource("com.mansereok.server.domain.review.service.ReviewServiceEligibilityTest#allowedOrders")
		@DisplayName("자격 조회는 쓸 수 있다고 답한다")
		void eligibilityCheckAllows(String situation, Order order) {
			// given
			givenOrderLookup(order, false);

			// when
			ReviewEligibilityResponse response = reviewService.checkReviewEligibility("writer", ORDER_ID,
				PRODUCT_ID);

			// then
			assertThat(response).isEqualTo(new ReviewEligibilityResponse(true, null, "리뷰 작성이 가능합니다."));
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@MethodSource("com.mansereok.server.domain.review.service.ReviewServiceEligibilityTest#allowedOrders")
		@DisplayName("작성은 요청한 회원, 상품, 주문으로 리뷰를 저장한다")
		void createReviewSaves(String situation, Order order) {
			// given
			givenOrderLookup(order, false);
			given(reviewRepository.save(any(Review.class))).willAnswer(invocation -> invocation.getArgument(0));

			// when
			reviewService.createReview("writer", request());

			// then
			ArgumentCaptor<Review> saved = ArgumentCaptor.forClass(Review.class);
			then(reviewRepository).should().save(saved.capture());
			assertThat(saved.getValue())
				.extracting(Review::getUserId, Review::getSubCategoryId, Review::getOrderId, Review::getContent)
				.containsExactly(WRITER_ID, PRODUCT_ID, ORDER_ID, CONTENT);
		}
	}

	/**
	 * 주문 조회 결과를 정한다. order 가 null 이면 주문이 없다.
	 *
	 * <p>이미 쓴 리뷰가 있는지 묻는 조회는 lenient 로 둔다. 앞 규칙에서 거절되는 줄에서는 불리지 않는 것이 맞는 동작이다. 불리지
	 * 않는다는 것 자체는 ReviewEligibilityPolicyTest 가 확인한다.
	 */
	private void givenOrderLookup(Order order, boolean alreadyWritten) {
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.ofNullable(order));
		lenient().when(reviewRepository.existsByOrderId(ORDER_ID)).thenReturn(alreadyWritten);
	}

	private static ReviewCreateRequest request() {
		ReviewCreateRequest request = new ReviewCreateRequest();
		request.setOrderId(ORDER_ID);
		request.setSubCategoryId(PRODUCT_ID);
		request.setContent(CONTENT);
		return request;
	}
}
