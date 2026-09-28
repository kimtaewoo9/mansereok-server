package com.mansereok.server.domain.interpret.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.interpret.dto.request.CompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseCompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseInterpretationRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCreateRequest;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.exception.InterpretationAlreadyStartedException;
import com.mansereok.server.domain.interpret.prompt.PromptFixtures;
import com.mansereok.server.domain.interpret.service.ManseCalculationService;
import com.mansereok.server.domain.interpret.service.ManseInterpretationService;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.service.PaymentService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

/**
 * 스레드 풀 포화로 비동기 제출이 거부되면, 제출 직전에 PROCESSING 으로 바꿔 둔 결과 상태를
 * 되돌리고 예외는 그대로 올려야 한다. 되돌리지 않으면 해석이 시작조차 하지 않은 요청이
 * PROCESSING 에 남아 사용자가 재시도도 못 한다.
 *
 * <p>해석 시작 표시가 거절되면(이미 해석 중이거나 완료) 해석을 제출하지도, 남의 상태를 되돌리지도 않아야 한다. 유료 경로는
 * 만세력 계산이 실패하면 결과 상태를 건드리지 않아야 한다.
 *
 * <p>해석 시작 표시가 돌려준 시각은 비동기 해석과 되돌리기에 그대로 넘어가야 한다. 해석과 되돌리기는 이 시각으로 자기가 시작한
 * 해석인지 가린다. 제출 거부 스텁은 그 시각을 eq 로 걸어, 다른 값을 넘기면 스텁이 맞지 않아 거부 예외가 나지 않고 테스트가 실패한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ManseryeokController 비동기 제출 거부")
class ManseryeokControllerRejectionTest {

	private static final String USERNAME = "user-1";
	private static final Long PAYMENT_ID = 100L;
	private static final Long FREE_PAYMENT_ID = 200L;
	private static final Long SUBCATEGORY_ID = 1L;
	private static final Long FREE_SUBCATEGORY_ID = 101L;
	private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 9, 26, 9, 0);

	@Mock
	private ManseCalculationService manseCalculationService;
	@Mock
	private ManseInterpretationService manseInterpretationService;
	@Mock
	private PaymentService paymentService;
	@Mock
	private ResultService resultService;

	private ManseryeokController controller;

	@BeforeEach
	void setUp() {
		controller = new ManseryeokController(manseCalculationService, manseInterpretationService,
			paymentService, resultService);

		ManseryeokCalculationResponse manse = PromptFixtures.person1();
		given(manseCalculationService.calculate(any())).willReturn(manse);
	}

	private void givenFreeOrder() {
		Payment payment = mock(Payment.class);
		given(payment.getId()).willReturn(FREE_PAYMENT_ID);
		given(paymentService.createFreeOrder(anyString(), anyLong())).willReturn(payment);
	}

	private ManseInterpretationRequest singleRequest() {
		ManseInterpretationRequest request = new ManseInterpretationRequest();
		request.setName("홍길동");
		request.setSolarDate(LocalDate.of(1990, 1, 1));
		request.setSolarTime(LocalTime.of(12, 0));
		request.setGender("MALE");
		request.setIsLunar(false);
		request.setPaymentId(PAYMENT_ID);
		return request;
	}

	private ManseCompatibilityAnalysisRequest paidCompatibilityRequest() {
		ManseCompatibilityAnalysisRequest request = new ManseCompatibilityAnalysisRequest();
		request.setPerson1(person("홍길동"));
		request.setPerson2(person("김영희"));
		request.setPaymentId(PAYMENT_ID);
		return request;
	}

	private ManseCompatibilityAnalysisRequest.PersonInfo person(String name) {
		ManseCompatibilityAnalysisRequest.PersonInfo person =
			new ManseCompatibilityAnalysisRequest.PersonInfo();
		person.setName(name);
		person.setSolarDate(LocalDate.of(1990, 1, 1));
		person.setSolarTime(LocalTime.of(12, 0));
		person.setGender("MALE");
		person.setIsLunar(false);
		return person;
	}

	private CompatibilityAnalysisRequest freeCompatibilityRequest() {
		CompatibilityAnalysisRequest request = new CompatibilityAnalysisRequest();
		request.setPerson1(freePerson("홍길동"));
		request.setPerson2(freePerson("김영희"));
		return request;
	}

	private ManseryeokCreateRequest freePerson(String name) {
		ManseryeokCreateRequest person = new ManseryeokCreateRequest();
		person.setName(name);
		person.setGender("MALE");
		person.setCalendar("S");
		person.setBirthday("1990/01/01");
		person.setBirthtime("12:00");
		return person;
	}

	private TaskRejectedException rejection() {
		return new TaskRejectedException("Executor did not accept task");
	}

	@Test
	@DisplayName("유료 단일 해석 제출이 거부되면 상태를 되돌리고 거부 예외를 그대로 올린다")
	void paidSingleRejectionRollsBackStatus() {
		given(resultService.startProcessing(PAYMENT_ID)).willReturn(STARTED_AT);
		willThrow(rejection()).given(manseInterpretationService)
			.interpret(anyString(), any(), anyString(), anyLong(), anyLong(), eq(STARTED_AT), any());

		assertThatThrownBy(() -> controller.interpret(SUBCATEGORY_ID, singleRequest(), USERNAME))
			.isInstanceOf(TaskRejectedException.class);

		InOrder inOrder = inOrder(resultService);
		inOrder.verify(resultService).startProcessing(PAYMENT_ID);
		inOrder.verify(resultService).rollbackStatusByPaymentId(PAYMENT_ID, STARTED_AT);
	}

	@Test
	@DisplayName("유료 궁합 제출이 거부되면 궁합 상태를 되돌리고 거부 예외를 그대로 올린다")
	void paidCompatibilityRejectionRollsBackStatus() {
		given(resultService.startCompatibilityProcessing(PAYMENT_ID)).willReturn(STARTED_AT);
		willThrow(rejection()).given(manseInterpretationService)
			.analyzeCompatibilityWithSubcategory(anyString(), any(), anyString(), any(),
				anyLong(), anyLong(), eq(STARTED_AT), anyString(), any(), any());

		assertThatThrownBy(() -> controller.analyzeCompatibility(
			SUBCATEGORY_ID, paidCompatibilityRequest(), USERNAME))
			.isInstanceOf(TaskRejectedException.class);

		InOrder inOrder = inOrder(resultService);
		inOrder.verify(resultService).startCompatibilityProcessing(PAYMENT_ID);
		inOrder.verify(resultService).rollbackCompatibilityStatusByPaymentId(PAYMENT_ID, STARTED_AT);
	}

	@Test
	@DisplayName("무료 단일 해석 제출이 거부되면 상태를 되돌리고 거부 예외를 그대로 올린다")
	void freeSingleRejectionRollsBackStatus() {
		givenFreeOrder();
		given(resultService.startProcessing(FREE_PAYMENT_ID)).willReturn(STARTED_AT);
		willThrow(rejection()).given(manseInterpretationService)
			.interpretFree(anyString(), any(), anyString(), anyLong(), anyLong(), eq(STARTED_AT));

		assertThatThrownBy(() -> controller.interpretFree(
			FREE_SUBCATEGORY_ID, singleRequest(), USERNAME))
			.isInstanceOf(TaskRejectedException.class);

		InOrder inOrder = inOrder(resultService);
		inOrder.verify(resultService).startProcessing(FREE_PAYMENT_ID);
		inOrder.verify(resultService).rollbackStatusByPaymentId(FREE_PAYMENT_ID, STARTED_AT);
	}

	@Test
	@DisplayName("무료 궁합 제출이 거부되면 궁합 상태를 되돌리고 거부 예외를 그대로 올린다")
	void freeCompatibilityRejectionRollsBackStatus() {
		givenFreeOrder();
		given(resultService.startCompatibilityProcessing(FREE_PAYMENT_ID)).willReturn(STARTED_AT);
		willThrow(rejection()).given(manseInterpretationService)
			.analyzeCompatibilityFree(anyString(), any(), anyString(), any(),
				anyLong(), anyLong(), eq(STARTED_AT), anyString());

		assertThatThrownBy(() -> controller.analyzeCompatibilityFree(
			FREE_SUBCATEGORY_ID, freeCompatibilityRequest(), USERNAME))
			.isInstanceOf(TaskRejectedException.class);

		InOrder inOrder = inOrder(resultService);
		inOrder.verify(resultService).startCompatibilityProcessing(FREE_PAYMENT_ID);
		inOrder.verify(resultService).rollbackCompatibilityStatusByPaymentId(FREE_PAYMENT_ID, STARTED_AT);
	}

	@Test
	@DisplayName("제출이 정상이면 해석을 시작한 시각을 비동기 해석에 넘기고 상태를 되돌리지 않는다")
	void successfulSubmissionDoesNotRollBack() {
		given(resultService.startProcessing(PAYMENT_ID)).willReturn(STARTED_AT);

		controller.interpret(SUBCATEGORY_ID, singleRequest(), USERNAME);

		verify(manseInterpretationService).interpret(eq("홍길동"), any(), eq(USERNAME), eq(SUBCATEGORY_ID),
			eq(PAYMENT_ID), eq(STARTED_AT), any());
		verify(resultService, never()).rollbackStatusByPaymentId(anyLong(), any());
	}

	@Nested
	@DisplayName("이미 해석 중이거나 완료된 결제로 요청하면")
	class AlreadyStarted {

		@Test
		@DisplayName("유료 단일은 409 용 예외를 그대로 올리고 해석을 제출하지도 상태를 되돌리지도 않는다")
		void paidSingleDoesNotSubmitOrRollBack() {
			// given
			willThrow(new InterpretationAlreadyStartedException(PAYMENT_ID))
				.given(resultService).startProcessing(PAYMENT_ID);

			// when & then
			assertThatThrownBy(() -> controller.interpret(SUBCATEGORY_ID, singleRequest(), USERNAME))
				.isInstanceOf(InterpretationAlreadyStartedException.class);
			verifyNoInteractions(manseInterpretationService);
			verify(resultService, never()).rollbackStatusByPaymentId(anyLong(), any());
		}

		@Test
		@DisplayName("유료 궁합은 409 용 예외를 그대로 올리고 해석을 제출하지도 상태를 되돌리지도 않는다")
		void paidCompatibilityDoesNotSubmitOrRollBack() {
			// given
			willThrow(new InterpretationAlreadyStartedException(PAYMENT_ID))
				.given(resultService).startCompatibilityProcessing(PAYMENT_ID);

			// when & then
			assertThatThrownBy(() -> controller.analyzeCompatibility(
				SUBCATEGORY_ID, paidCompatibilityRequest(), USERNAME))
				.isInstanceOf(InterpretationAlreadyStartedException.class);
			verifyNoInteractions(manseInterpretationService);
			verify(resultService, never()).rollbackCompatibilityStatusByPaymentId(anyLong(), any());
		}
	}

	@Nested
	@DisplayName("유료 경로에서 만세력 계산이 실패하면")
	class CalculationFails {

		@Test
		@DisplayName("유료 단일은 계산 예외를 그대로 올리고 결과 상태를 건드리지 않는다")
		void paidSingleLeavesStatus() {
			// given
			given(manseCalculationService.calculate(any()))
				.willThrow(new IllegalArgumentException("지원하지 않는 성별 값입니다"));

			// when & then
			assertThatThrownBy(() -> controller.interpret(SUBCATEGORY_ID, singleRequest(), USERNAME))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("지원하지 않는 성별 값입니다");
			verifyNoInteractions(resultService, manseInterpretationService);
		}

		@Test
		@DisplayName("유료 궁합은 두 번째 사람 계산만 실패해도 결과 상태를 건드리지 않는다")
		void paidCompatibilityLeavesStatusWhenSecondPersonFails() {
			// given
			given(manseCalculationService.calculate(any()))
				.willReturn(PromptFixtures.person1())
				.willThrow(new IllegalArgumentException("지원하지 않는 성별 값입니다"));

			// when & then
			assertThatThrownBy(() -> controller.analyzeCompatibility(
				SUBCATEGORY_ID, paidCompatibilityRequest(), USERNAME))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("지원하지 않는 성별 값입니다");
			verifyNoInteractions(resultService, manseInterpretationService);
		}
	}
}
