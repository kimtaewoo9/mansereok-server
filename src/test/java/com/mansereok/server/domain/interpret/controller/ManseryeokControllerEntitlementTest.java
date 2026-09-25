package com.mansereok.server.domain.interpret.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mansereok.server.domain.interpret.service.ManseCalculationService;
import com.mansereok.server.domain.interpret.service.ManseInterpretationService;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.service.PaymentEntitlementService;
import com.mansereok.server.domain.payment.service.PaymentOrderService;
import com.mansereok.server.global.exception.GlobalExceptionHandler;
import com.mansereok.server.global.exception.PaymentException;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 해석 API 가 경로의 상품 id 로 해석 시작을 확인받는지, 거부되면 해석을 제출하지 않는지 검증한다.
 *
 * <p>스프링 컨텍스트 없이 컨트롤러 하나만 MockMvc 에 올린다. 결제 확인(PaymentEntitlementService)과 해석 서비스는 목이다. 결제
 * 확인 스텁은 결제 PK·요청자·상품 id 를 정확한 값으로 걸어, 컨트롤러가 다른 값을 넘기면 strict stubs 가 테스트를 실패시킨다.
 * 허용하는 스텁은 넘겨받은 결과 변경을 실제로 불러, 일반 사주와 궁합이 각자의 결과를 바꾸는 동작을 넘기는지도 본다.
 */
@ExtendWith(MockitoExtension.class)
class ManseryeokControllerEntitlementTest {

	private static final String USERNAME = "entitlement_user";
	private static final Long PAYMENT_PK_ID = 100L;
	private static final String REJECTED_MESSAGE = "유효한 결제 정보가 아닙니다.";

	@Mock
	private ManseCalculationService manseCalculationService;
	@Mock
	private ManseInterpretationService manseInterpretationService;
	@Mock
	private PaymentEntitlementService paymentEntitlementService;
	@Mock
	private PaymentOrderService paymentOrderService;
	@Mock
	private ResultService resultService;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		ManseryeokController controller = new ManseryeokController(manseCalculationService,
			manseInterpretationService, paymentEntitlementService, paymentOrderService, resultService);
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler())
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.build();
		// JwtAuthenticationFilter 처럼 principal 에 username 문자열을 넣는다.
		SecurityContextHolder.getContext().setAuthentication(
			new UsernamePasswordAuthenticationToken(USERNAME, null, List.of()));
	}

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Nested
	@DisplayName("유료 사주 해석을 요청하면")
	class PaidInterpretation {

		@Test
		@DisplayName("경로의 상품 13 으로 해석 시작을 확인받고, 허용되면 일반 사주 결과를 PROCESSING 으로 바꾼 뒤 상품 13 해석을 제출한다")
		void startsWithPathProductThenSubmits() throws Exception {
			// given
			willAnswer(runPassedResultChange())
				.given(paymentEntitlementService).startInterpretation(eq(PAYMENT_PK_ID), eq(USERNAME), eq(13L), any());

			// when & then
			mockMvc.perform(post("/api/v1/manseryeok/interpret/13")
					.contentType(MediaType.APPLICATION_JSON)
					.content(interpretationBody(PAYMENT_PK_ID)))
				.andExpect(status().isAccepted());
			then(resultService).should().updateStatusToProcessing(PAYMENT_PK_ID);
			then(manseInterpretationService).should()
				.interpret(eq("홍길동"), any(), eq(USERNAME), eq(13L), eq(PAYMENT_PK_ID), any());
		}

		@Test
		@DisplayName("결제한 상품과 다른 상품 5 로 불러 해석 시작이 거부되면 400 PAYMENT_ERROR 로 답하고 해석을 제출하지 않는다")
		void rejectedStartDoesNotSubmit() throws Exception {
			// given
			willThrow(new PaymentException(REJECTED_MESSAGE))
				.given(paymentEntitlementService).startInterpretation(eq(PAYMENT_PK_ID), eq(USERNAME), eq(5L), any());

			// when & then
			mockMvc.perform(post("/api/v1/manseryeok/interpret/5")
					.contentType(MediaType.APPLICATION_JSON)
					.content(interpretationBody(PAYMENT_PK_ID)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("PAYMENT_ERROR"))
				.andExpect(jsonPath("$.message").value(REJECTED_MESSAGE));
			verifyNoInteractions(manseInterpretationService, resultService);
		}
	}

	@Nested
	@DisplayName("유료 궁합 해석을 요청하면")
	class PaidCompatibility {

		@Test
		@DisplayName("경로의 상품 19 로 해석 시작을 확인받고, 허용되면 궁합 결과를 PROCESSING 으로 바꾼 뒤 상품 19 궁합 해석을 제출한다")
		void startsWithPathProductThenSubmits() throws Exception {
			// given
			willAnswer(runPassedResultChange())
				.given(paymentEntitlementService).startInterpretation(eq(PAYMENT_PK_ID), eq(USERNAME), eq(19L), any());

			// when & then
			mockMvc.perform(post("/api/v1/manseryeok/interpret/compatibility/19")
					.contentType(MediaType.APPLICATION_JSON)
					.content(compatibilityBody(PAYMENT_PK_ID)))
				.andExpect(status().isAccepted());
			then(resultService).should().updateCompatibilityStatusToProcessing(PAYMENT_PK_ID);
			then(manseInterpretationService).should().analyzeCompatibilityWithSubcategory(
				eq("홍길동"), any(), eq("성춘향"), any(), eq(19L), eq(PAYMENT_PK_ID), eq(USERNAME), any(), any());
		}

		@Test
		@DisplayName("상품 4 결제로 상품 19 를 불러 해석 시작이 거부되면 400 PAYMENT_ERROR 로 답하고 궁합 해석을 제출하지 않는다")
		void rejectedStartDoesNotSubmit() throws Exception {
			// given
			willThrow(new PaymentException(REJECTED_MESSAGE))
				.given(paymentEntitlementService).startInterpretation(eq(PAYMENT_PK_ID), eq(USERNAME), eq(19L), any());

			// when & then
			mockMvc.perform(post("/api/v1/manseryeok/interpret/compatibility/19")
					.contentType(MediaType.APPLICATION_JSON)
					.content(compatibilityBody(PAYMENT_PK_ID)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("PAYMENT_ERROR"))
				.andExpect(jsonPath("$.message").value(REJECTED_MESSAGE));
			verifyNoInteractions(manseInterpretationService, resultService);
		}
	}

	@Nested
	@DisplayName("무료 해석을 요청하면")
	class FreeInterpretation {

		private static final Long FREE_PAYMENT_PK_ID = 200L;

		@Test
		@DisplayName("방금 만든 0원 결제와 경로의 상품 101 로 해석 시작을 확인받고, 일반 사주 결과를 PROCESSING 으로 바꾼 뒤 무료 해석을 제출한다")
		void freeSajuStartsWithCreatedPayment() throws Exception {
			// given
			given(paymentOrderService.createFreeOrder(USERNAME, 101L)).willReturn(freePayment(101L));
			willAnswer(runPassedResultChange()).given(paymentEntitlementService)
				.startInterpretation(eq(FREE_PAYMENT_PK_ID), eq(USERNAME), eq(101L), any());

			// when & then
			mockMvc.perform(post("/api/v1/manseryeok/interpret/free/101")
					.contentType(MediaType.APPLICATION_JSON)
					.content(interpretationBody(null)))
				.andExpect(status().isAccepted());
			then(resultService).should().updateStatusToProcessing(FREE_PAYMENT_PK_ID);
			then(manseInterpretationService).should()
				.interpretFree(eq("홍길동"), any(), eq(USERNAME), eq(101L), eq(FREE_PAYMENT_PK_ID));
		}

		@Test
		@DisplayName("방금 만든 0원 결제와 경로의 상품 104 로 해석 시작을 확인받고, 궁합 결과를 PROCESSING 으로 바꾼 뒤 무료 궁합 해석을 제출한다")
		void freeCompatibilityStartsWithCreatedPayment() throws Exception {
			// given
			given(paymentOrderService.createFreeOrder(USERNAME, 104L)).willReturn(freePayment(104L));
			willAnswer(runPassedResultChange()).given(paymentEntitlementService)
				.startInterpretation(eq(FREE_PAYMENT_PK_ID), eq(USERNAME), eq(104L), any());

			// when & then
			mockMvc.perform(post("/api/v1/manseryeok/compatibility/free/104")
					.contentType(MediaType.APPLICATION_JSON)
					.content(freeCompatibilityBody()))
				.andExpect(status().isAccepted());
			then(resultService).should().updateCompatibilityStatusToProcessing(FREE_PAYMENT_PK_ID);
			then(manseInterpretationService).should().analyzeCompatibilityFree(
				eq("홍길동"), any(), eq("성춘향"), any(), eq(104L), eq(FREE_PAYMENT_PK_ID), eq(USERNAME));
		}

		private Payment freePayment(Long subCategoryId) {
			Payment payment = Payment.create("free_pay_test", "free_order_test", 0L, PaymentStatus.PAID, 20L, 1L,
				subCategoryId);
			ReflectionTestUtils.setField(payment, "id", FREE_PAYMENT_PK_ID);
			return payment;
		}
	}

	/**
	 * 해석 시작을 허용하는 스텁. 실제 서비스처럼 넘겨받은 결과 변경(네 번째 인자)을 결제 PK(첫 번째 인자)로 한 번 부른다.
	 */
	private static Answer<Void> runPassedResultChange() {
		return invocation -> {
			Consumer<Long> markResultProcessing = invocation.getArgument(3);
			markResultProcessing.accept(invocation.getArgument(0));
			return null;
		};
	}

	private static String interpretationBody(Long paymentId) {
		return """
			{"name": "홍길동", "solarDate": "1990-01-01", "solarTime": "12:00", "gender": "M",
			 "isLunar": false, "leapMonth": false, "sourceTitle": "원피스", "paymentId": %s}
			""".formatted(paymentId);
	}

	private static String compatibilityBody(Long paymentId) {
		return """
			{"person1": {"name": "홍길동", "solarDate": "1990-01-01", "solarTime": "12:00", "gender": "M",
			             "isLunar": false, "leapMonth": false},
			 "person2": {"name": "성춘향", "solarDate": "1991-02-02", "solarTime": "08:30", "gender": "F",
			             "isLunar": false, "leapMonth": false},
			 "paymentId": %s}
			""".formatted(paymentId);
	}

	private static String freeCompatibilityBody() {
		return """
			{"person1": {"name": "홍길동", "gender": "M", "calendar": "S", "leapMonth": false,
			             "birthday": "1990/01/01", "birthtime": "12:00"},
			 "person2": {"name": "성춘향", "gender": "F", "calendar": "S", "leapMonth": false,
			             "birthday": "1991/02/02", "birthtime": "08:30"}}
			""";
	}
}
