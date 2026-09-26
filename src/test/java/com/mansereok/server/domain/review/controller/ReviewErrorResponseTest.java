package com.mansereok.server.domain.review.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.review.service.RejectionReason;
import com.mansereok.server.domain.review.service.ReviewService;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.Role;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.GlobalExceptionHandler;
import com.mansereok.server.global.exception.RequestErrorExceptionHandler;
import com.mansereok.server.global.exception.ReviewExceptionHandler;
import com.mansereok.server.support.fixture.OrderFixture;
import com.mansereok.server.support.fixture.UserFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 리뷰 작성 API 가 거절할 때 이유에 맞는 상태 코드와 리뷰 전용 errorCode REVIEW_NOT_ALLOWED 로 답하는지 확인한다. 예전에는 결제
 * 오류(PAYMENT_ERROR, 400)로 답했고, 탈퇴한 회원의 주문이면 500 이 났다.
 *
 * <p>운영과 같게 세 예외 처리기를 모두 등록하고 ReviewExceptionHandler 를 가장 나중에 등록한다. 그래도 먼저 답하는 것은 등록 순서가
 * 아니라 @Order(HIGHEST_PRECEDENCE) 덕분임을 확인하기 위해서다.
 *
 * <p>컨트롤러, 서비스, 예외 처리기는 진짜를 쓰고 저장소는 돌려줄 값만 정한다. 지금은 한국 시각 2026-09-25 00:00 으로 고정한다.
 * 로그인한 요청은 JWT 필터처럼 SecurityContextHolder 에 회원 아이디를 principal 로 넣어 흉내 낸다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ReviewErrorResponseTest {

	private static final Clock NOW = Clock.fixed(Instant.parse("2026-09-24T15:00:00Z"), ZoneId.of("Asia/Seoul"));
	private static final long ORDER_ID = 100L;
	private static final String REVIEW_BODY = """
		{"subCategoryId": 3, "orderId": 100, "content": "풀이가 자세하고 이해하기 쉬워서 많은 도움이 되었습니다."}
		""";

	@Mock
	private ReviewRepository reviewRepository;
	@Mock
	private OrderRepository orderRepository;
	@Mock
	private UserRepository userRepository;

	// 생성자 주입. 이 테스트가 목으로 두지 않은 UserService 의 협력 객체는 null 로 들어가며, 회원 조회에서는 쓰이지 않는다.
	@InjectMocks
	private UserService userService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		ReviewService reviewService = new ReviewService(reviewRepository, orderRepository, userService, NOW);
		mockMvc = MockMvcBuilders.standaloneSetup(new ReviewController(reviewService))
			.setControllerAdvice(new GlobalExceptionHandler(), new RequestErrorExceptionHandler(),
				new ReviewExceptionHandler())
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.build();
		givenLoggedIn("writer", 10L);
	}

	@AfterEach
	void clearLogin() {
		SecurityContextHolder.clearContext();
	}

	static Stream<Arguments> rejectedWrites() {
		return Stream.of(
			Arguments.of("이미 리뷰를 쓴 주문", OrderFixture.paidOrder().build(), true, 409,
				RejectionReason.ALREADY_WRITTEN),
			Arguments.of("결제 후 31일째 주문",
				OrderFixture.paidOrder().paidAt(LocalDateTime.of(2026, 8, 25, 23, 59)).build(), false, 400,
				RejectionReason.EXPIRED),
			Arguments.of("환불한 주문", OrderFixture.paidOrder().status(OrderStatus.CANCELLED).build(), false, 400,
				RejectionReason.NOT_PAID),
			Arguments.of("다른 상품의 주문", OrderFixture.paidOrder().subCategoryId(4L).build(), false, 400,
				RejectionReason.MISMATCH_PRODUCT),
			Arguments.of("남의 주문", OrderFixture.paidOrder().userId(11L).build(), false, 403,
				RejectionReason.NOT_OWNER),
			Arguments.of("탈퇴한 회원의 주문(user_id NULL)", OrderFixture.paidOrder().userId(null).build(), false, 403,
				RejectionReason.NOT_OWNER),
			Arguments.of("없는 주문", null, false, 404, RejectionReason.ORDER_NOT_FOUND)
		);
	}

	@ParameterizedTest(name = "[{index}] {0} → {3} {4}")
	@MethodSource("rejectedWrites")
	@DisplayName("리뷰를 쓸 수 없는 주문으로 쓰면 이유에 맞는 상태 코드와 errorCode REVIEW_NOT_ALLOWED, 이유의 문구로 답한다")
	void rejectedWriteIsAnsweredWithReviewErrorCode(String situation, Order order, boolean alreadyWritten,
		int expectedStatus, RejectionReason expectedReason) throws Exception {
		// given
		givenOrderLookup(order, alreadyWritten);

		// when
		ResultActions result = mockMvc.perform(
			post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(REVIEW_BODY));

		// then
		result.andExpect(status().is(expectedStatus))
			.andExpect(jsonPath("$.status").value(expectedStatus))
			.andExpect(jsonPath("$.errorCode").value("REVIEW_NOT_ALLOWED"))
			.andExpect(jsonPath("$.message").value(expectedReason.getMessage()));
	}

	@Test
	@DisplayName("리뷰 거절은 결제 오류 로그가 아니라 리뷰 거절 로그로 남는다")
	void rejectedWriteIsLoggedAsReviewRejection(CapturedOutput output) throws Exception {
		// given
		givenOrderLookup(OrderFixture.paidOrder().build(), true);

		// when
		mockMvc.perform(post("/api/v1/reviews").contentType(MediaType.APPLICATION_JSON).content(REVIEW_BODY));

		// then
		assertThat(output.getAll())
			.contains("리뷰 작성 거절: reason=ALREADY_WRITTEN")
			.doesNotContain("결제/주문 비즈니스 예외");
	}

	@Test
	@DisplayName("탈퇴한 회원의 주문으로 자격을 물으면 500 이 아니라 200 과 NOT_OWNER 로 답한다")
	void eligibilityOfDetachedOrderIsNotOwner() throws Exception {
		// given
		givenOrderLookup(OrderFixture.paidOrder().userId(null).build(), false);

		// when
		ResultActions result = mockMvc.perform(get("/api/v1/reviews/eligibility")
			.param("orderId", String.valueOf(ORDER_ID)).param("subCategoryId", "3"));

		// then
		result.andExpect(status().isOk())
			.andExpect(jsonPath("$.eligible").value(false))
			.andExpect(jsonPath("$.reason").value("NOT_OWNER"))
			.andExpect(jsonPath("$.message").value("본인의 주문에 대해서만 리뷰를 작성할 수 있습니다."));
	}

	/**
	 * 주문 조회 결과를 정한다. order 가 null 이면 주문이 없다. 이미 쓴 리뷰가 있는지 묻는 조회는 앞 규칙에서 거절되는 줄에서는 불리지
	 * 않으므로 lenient 로 둔다.
	 */
	private void givenOrderLookup(Order order, boolean alreadyWritten) {
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.ofNullable(order));
		lenient().when(reviewRepository.existsByOrderId(ORDER_ID)).thenReturn(alreadyWritten);
	}

	/**
	 * JWT 필터가 하는 것처럼 회원 아이디를 principal 로 넣고, 그 회원을 DB 에서 찾으면 주어진 id 의 회원이 나오게 한다.
	 */
	private void givenLoggedIn(String username, Long userId) {
		User user = User.create(username, "홍길동", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false);
		UserFixture.withId(user, userId);
		given(userRepository.findByUsername(username)).willReturn(Optional.of(user));
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
			username, null, List.of(new SimpleGrantedAuthority(Role.USER.getAuthority()))));
	}
}
