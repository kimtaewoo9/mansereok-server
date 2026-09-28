package com.mansereok.server.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.review.dto.request.ReviewCreateRequest;
import com.mansereok.server.domain.review.dto.response.ReviewResponse;
import com.mansereok.server.domain.review.entity.Review;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.ReviewNotAllowedException;
import com.mansereok.server.support.fixture.OrderFixture;
import com.mansereok.server.support.fixture.UserFixture;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 리뷰를 쓸 수 있는 주문으로 작성할 때 무엇을 저장하는지, 저장 중 DB 제약 위반이 나면 어떻게 답하는지 확인한다. 쓸 수 없는 주문의
 * 거절은 ReviewServiceEligibilityTest 가 본다.
 *
 * <p>UNIQUE 위반이 실제 MySQL 에서 이 모양으로 오는지는 ReviewConcurrencyMySqlTest 가 같은 주문을 동시에 제출해 확인한다. 여기서는
 * 저장소가 돌려줄 결과(저장된 리뷰나 예외)만 정한다. 회원 조회는 진짜 {@link UserService} 가 한다.
 *
 * <p>회원 id(10), 주문 id(100), 상품 id(3)를 모두 다르게 두어 두 값이 뒤바뀌면 드러나게 한다. 지금은 한국 시각 2026-09-25
 * 00:00 이고, 주문은 2026-09-10 에 결제한 PAID 주문이라 기한 안이다.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceWriteTest {

	private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-24T15:00:00Z"), ZoneId.of("Asia/Seoul"));
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
		User writer = User.create("writer", "홍길동", "password", "hong@example.com", LocalDate.of(1990, 1, 1),
			Gender.MALE, true, true, false);
		UserFixture.withId(writer, 10L);
		given(userRepository.findByUsername("writer")).willReturn(Optional.of(writer));
		given(orderRepository.findById(100L)).willReturn(
			Optional.of(OrderFixture.paidOrder().id(100L).userId(10L).subCategoryId(3L).build()));
		given(reviewRepository.existsByOrderId(100L)).willReturn(false);
	}

	@Nested
	@DisplayName("저장이 성공하면")
	class WhenSaved {

		@Test
		@DisplayName("작성자 id·이름, 주문의 상품 id·주문 id, 본문으로 리뷰를 저장하고 이메일은 저장하지 않는다")
		void savesReviewWithValuesFromUserAndOrder() {
			// given
			given(reviewRepository.saveAndFlush(any(Review.class))).willAnswer(invocation -> {
				Review review = invocation.getArgument(0);
				ReflectionTestUtils.setField(review, "id", 7L);
				return review;
			});

			// when
			ReviewResponse response = reviewService.createReview("writer", request());

			// then
			ArgumentCaptor<Review> saved = ArgumentCaptor.forClass(Review.class);
			then(reviewRepository).should().saveAndFlush(saved.capture());
			assertThat(saved.getValue())
				.extracting(Review::getUserId, Review::getUserName, Review::getSubCategoryId, Review::getOrderId,
					Review::getContent, Review::getUserEmail)
				.containsExactly(10L, "홍길동", 3L, 100L, CONTENT, null);
			assertThat(response)
				.extracting(ReviewResponse::reviewId, ReviewResponse::subCategoryId, ReviewResponse::userName)
				.containsExactly(7L, 3L, "홍*동");
		}
	}

	@Nested
	@DisplayName("저장 중 DB 제약 위반이 나면")
	class WhenSaveViolatesConstraint {

		@Test
		@DisplayName("UNIQUE(order_id) 위반이면 같은 주문으로 먼저 저장된 리뷰가 있는 것이라 ALREADY_WRITTEN 으로 거절하고 원인을 남긴다")
		void uniqueViolationBecomesAlreadyWritten() {
			// given
			DataIntegrityViolationException duplicateOrder = uniqueViolationOfOrderId();
			given(reviewRepository.saveAndFlush(any(Review.class))).willThrow(duplicateOrder);

			// when & then
			assertThatThrownBy(() -> reviewService.createReview("writer", request()))
				.isInstanceOfSatisfying(ReviewNotAllowedException.class,
					e -> assertThat(e.getReason()).isEqualTo(RejectionReason.ALREADY_WRITTEN))
				.hasMessage("이미 해당 주문에 대한 리뷰를 작성하셨습니다.")
				.hasCause(duplicateOrder);
		}

		@Test
		@DisplayName("UNIQUE 가 아닌 위반(NOT NULL)이면 이미 쓴 리뷰로 바꾸지 않고 그대로 던진다")
		void otherViolationIsRethrownAsIs() {
			// given
			DataIntegrityViolationException notNull = notNullViolationOfContent();
			given(reviewRepository.saveAndFlush(any(Review.class))).willThrow(notNull);

			// when & then
			assertThatThrownBy(() -> reviewService.createReview("writer", request()))
				.isSameAs(notNull);
		}
	}

	private static ReviewCreateRequest request() {
		ReviewCreateRequest request = new ReviewCreateRequest();
		request.setOrderId(100L);
		request.setSubCategoryId(3L);
		request.setContent(CONTENT);
		return request;
	}

	// Hibernate MySQL 방언이 중복 키(1062)에 만드는 모양. 실제로 이 모양이 되는지는 UniqueConstraintViolationsMySqlTest 가 본다.
	private static DataIntegrityViolationException uniqueViolationOfOrderId() {
		return new DataIntegrityViolationException("could not execute statement",
			new ConstraintViolationException("could not execute statement",
				new SQLIntegrityConstraintViolationException(
					"Duplicate entry '100' for key 'reviews.uk_reviews_order_id'", "23000", 1062),
				"insert into reviews (order_id) values (?)", ConstraintKind.UNIQUE, "reviews.uk_reviews_order_id"));
	}

	// NOT NULL 위반(1048)은 종류를 따로 적지 않아 ConstraintKind.OTHER 가 된다.
	private static DataIntegrityViolationException notNullViolationOfContent() {
		return new DataIntegrityViolationException("could not execute statement",
			new ConstraintViolationException("could not execute statement",
				new SQLIntegrityConstraintViolationException("Column 'content' cannot be null", "23000", 1048),
				"insert into reviews (content) values (?)"));
	}
}
