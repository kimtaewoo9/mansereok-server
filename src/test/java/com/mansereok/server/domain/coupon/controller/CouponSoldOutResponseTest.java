package com.mansereok.server.domain.coupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mansereok.server.domain.coupon.repository.CouponRepository;
import com.mansereok.server.domain.coupon.repository.CouponTemplateRepository;
import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.payment.service.PaymentUserLookup;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.GlobalExceptionHandler;
import com.mansereok.server.global.exception.RequestErrorExceptionHandler;
import com.mansereok.server.support.fixture.CouponTemplateFixture;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 선착순 마감된 쿠폰을 받으려는 요청이 500 이 아니라 400 PAYMENT_ERROR 로 나가는지 고정한다.
 *
 * <p>마감은 이벤트가 열린 직후 사람이 몰리면 늦게 온 요청 대부분이 받는 응답이다. 500 "서버 내부 오류" 로 나가면 사용자는 마감
 * 안내 대신 재시도를 반복하고, 요청마다 스택 트레이스가 붙은 ERROR 로그가 쌓여 실제 장애 알림을 가린다.
 *
 * <p>운영과 같은 쿠폰 컨트롤러와 쿠폰 서비스를 쓰고 리포지토리만 목으로 바꾼다. 그래서 템플릿의 마감 판정부터 예외 처리기가 응답을
 * 고르기까지 운영 코드가 그대로 돈다. 로그인 필터가 하던 인증 정보 넣기(요청자 이름)는 테스트가 대신한다.
 */
@ExtendWith(MockitoExtension.class)
class CouponSoldOutResponseTest {

	private static final String USERNAME = "buyer";
	private static final Long USER_ID = 3L;
	private static final Long TEMPLATE_ID = 11L;

	@Mock
	private CouponRepository couponRepository;
	@Mock
	private CouponTemplateRepository couponTemplateRepository;
	@Mock
	private UserRepository userRepository;

	private MockMvc mockMvc;

	private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	@BeforeEach
	void setUp() {
		CouponService couponService = new CouponService(couponRepository, couponTemplateRepository);
		mockMvc = MockMvcBuilders.standaloneSetup(new CouponController(couponService, new PaymentUserLookup(userRepository)))
			.setControllerAdvice(new GlobalExceptionHandler(), new RequestErrorExceptionHandler())
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.build();
		SecurityContextHolder.getContext().setAuthentication(
			new UsernamePasswordAuthenticationToken(USERNAME, null, List.of()));
		logs.start();
		handlerLogger.addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		SecurityContextHolder.clearContext();
		handlerLogger.detachAppender(logs);
	}

	@Test
	@DisplayName("상한만큼 발급된 쿠폰을 받으려 하면 400 PAYMENT_ERROR 와 '선착순 마감되었습니다.' 를 돌려준다")
	void soldOutIsBadRequest() throws Exception {
		// given
		givenSoldOutTemplateNotYetIssuedToUser();

		// when & then
		mockMvc.perform(post("/api/coupons/{templateId}/download", TEMPLATE_ID))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.errorCode").value("PAYMENT_ERROR"))
			.andExpect(jsonPath("$.message").value("선착순 마감되었습니다."));
	}

	@Test
	@DisplayName("마감 응답은 스택 트레이스 없는 WARN 한 줄만 남기고 ERROR 로그를 남기지 않는다")
	void soldOutLogsSingleWarnWithoutStackTrace() throws Exception {
		// given
		givenSoldOutTemplateNotYetIssuedToUser();

		// when
		mockMvc.perform(post("/api/coupons/{templateId}/download", TEMPLATE_ID));

		// then
		assertThat(logs.list).singleElement().satisfies(event -> {
			assertThat(event.getLevel()).isEqualTo(Level.WARN);
			assertThat(event.getFormattedMessage()).contains("선착순 마감되었습니다.");
			assertThat(event.getThrowableProxy()).as("스택 트레이스를 남기지 않는다").isNull();
		});
	}

	private void givenSoldOutTemplateNotYetIssuedToUser() {
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(user()));
		given(couponTemplateRepository.findByIdWithLock(TEMPLATE_ID)).willReturn(Optional.of(
			CouponTemplateFixture.issuableNow().id(TEMPLATE_ID).maxIssueCount(100).currentIssueCount(100).build()));
		given(couponRepository.existsByUserIdAndTemplateId(USER_ID, TEMPLATE_ID)).willReturn(false);
	}

	private static User user() {
		User user = User.create(USERNAME, "구매자", "password", "buyer@example.com", LocalDate.of(1990, 1, 1),
			Gender.FEMALE, true, true, false);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}
}
