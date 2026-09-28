package com.mansereok.server.domain.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.review.entity.Review;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.Role;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.support.fixture.ReviewFixture;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * 리뷰 삭제는 관리자(ADMIN, SUPER_ADMIN)만 할 수 있다는 규칙을 역할 전체에 대해 확인한다.
 *
 * <p>요청자 조회는 진짜 {@link UserService} 가 한다. 목으로 두는 것은 저장소(돌려줄 값만 정한다)뿐이다. 관리자가 아닌 쪽 표는
 * 관리자 두 역할을 뺀 나머지 전부라, 역할을 새로 더하면 따로 고치지 않아도 "지울 수 없다" 쪽으로 검사된다.
 */
@ExtendWith(MockitoExtension.class)
class ReviewServiceDeleteTest {

	private static final long REVIEW_ID = 7L;

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
		reviewService = new ReviewService(reviewRepository, orderRepository, userService);
	}

	@Nested
	@DisplayName("관리자가 아닌 회원이 지우려 하면")
	class WhenRequesterIsNotAdmin {

		@ParameterizedTest(name = "[{index}] {0} 요청 → 403, 리뷰는 그대로")
		@EnumSource(value = Role.class, mode = Mode.EXCLUDE, names = {"ADMIN", "SUPER_ADMIN"})
		@DisplayName("403(AccessDeniedException)을 내고 리뷰를 삭제 표시하지 않는다")
		void deniesAndKeepsReview(Role role) {
			// given
			givenRequester("member", role);
			Review review = ReviewFixture.review().id(REVIEW_ID).build();
			// 옳은 코드는 권한 검사에서 멈춰 리뷰를 조회하지 않으므로 lenient 로 둔다. 조회해 지운 뒤에 권한을 본다면 아래
			// isDeleted 단언이 실패한다.
			lenient().when(reviewRepository.findById(REVIEW_ID)).thenReturn(Optional.of(review));

			// when & then
			assertThatThrownBy(() -> reviewService.deleteReview(REVIEW_ID, "member"))
				.isInstanceOf(AccessDeniedException.class)
				.hasMessage("리뷰는 관리자만 삭제할 수 있습니다.");
			assertThat(review.isDeleted()).isFalse();
		}

		@ParameterizedTest(name = "[{index}] {0} 요청, 없는 리뷰 번호 → 404 가 아니라 403")
		@EnumSource(value = Role.class, mode = Mode.EXCLUDE, names = {"ADMIN", "SUPER_ADMIN"})
		@DisplayName("없는 리뷰 번호여도 404 가 아니라 403 을 내서 리뷰가 있는지 알려 주지 않는다")
		void deniesBeforeLookingUpReview(Role role) {
			// given: 리뷰 조회를 스텁하지 않는다. 권한보다 조회를 먼저 하면 빈 결과를 받아 404 로 끝난다.
			givenRequester("member", role);

			// when & then
			assertThatThrownBy(() -> reviewService.deleteReview(99L, "member"))
				.isInstanceOf(AccessDeniedException.class)
				.hasMessage("리뷰는 관리자만 삭제할 수 있습니다.");
		}
	}

	@Nested
	@DisplayName("관리자가 지우면")
	class WhenRequesterIsAdmin {

		@ParameterizedTest(name = "[{index}] {0} 요청 → 삭제 표시")
		@EnumSource(value = Role.class, names = {"ADMIN", "SUPER_ADMIN"})
		@DisplayName("리뷰를 삭제 표시한다")
		void marksReviewAsDeleted(Role role) {
			// given
			givenRequester("admin", role);
			Review review = ReviewFixture.review().id(REVIEW_ID).build();
			given(reviewRepository.findById(REVIEW_ID)).willReturn(Optional.of(review));

			// when
			reviewService.deleteReview(REVIEW_ID, "admin");

			// then
			assertThat(review.isDeleted()).isTrue();
		}

		@Test
		@DisplayName("없는 리뷰 번호면 404(EntityNotFoundException)를 낸다")
		void failsWhenReviewDoesNotExist() {
			// given
			givenRequester("admin", Role.ADMIN);
			given(reviewRepository.findById(99L)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> reviewService.deleteReview(99L, "admin"))
				.isInstanceOf(EntityNotFoundException.class)
				.hasMessage("해당 리뷰를 찾을 수 없습니다: 99");
		}
	}

	private void givenRequester(String username, Role role) {
		User requester = User.create(username, "요청자", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false);
		requester.setRole(role);
		given(userRepository.findByUsername(username)).willReturn(Optional.of(requester));
	}
}
