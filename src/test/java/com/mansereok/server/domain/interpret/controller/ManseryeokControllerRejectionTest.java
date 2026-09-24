package com.mansereok.server.domain.interpret.controller;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.mansereok.server.domain.interpret.dto.request.CompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseCompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseInterpretationRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCreateRequest;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.prompt.PromptFixtures;
import com.mansereok.server.domain.interpret.service.ManseCalculationService;
import com.mansereok.server.domain.interpret.service.ManseInterpretationService;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.service.PaymentService;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;

/**
 * 스레드 풀 포화로 비동기 제출이 거부되면, 제출 직전에 PROCESSING 으로 바꿔 둔 결과 상태를
 * 되돌리고 예외는 그대로 올려야 한다. 되돌리지 않으면 해석이 시작조차 하지 않은 요청이
 * 영원히 PROCESSING 에 남아 사용자가 재시도도 못 한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ManseryeokController 비동기 제출 거부")
class ManseryeokControllerRejectionTest {

	private static final String USERNAME = "user-1";
	private static final Long PAYMENT_ID = 100L;
	private static final Long FREE_PAYMENT_ID = 200L;
	private static final Long SUBCATEGORY_ID = 1L;
	private static final Long FREE_SUBCATEGORY_ID = 101L;

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
		willThrow(rejection()).given(manseInterpretationService)
			.interpret(anyString(), any(), anyString(), anyLong(), anyLong(), any());

		assertThatThrownBy(() -> controller.interpret(SUBCATEGORY_ID, singleRequest(), USERNAME))
			.isInstanceOf(TaskRejectedException.class);

		InOrder inOrder = inOrder(resultService);
		inOrder.verify(resultService).updateStatusToProcessing(PAYMENT_ID);
		inOrder.verify(resultService).rollbackStatusByPaymentId(PAYMENT_ID);
	}

	@Test
	@DisplayName("유료 궁합 제출이 거부되면 궁합 상태를 되돌리고 거부 예외를 그대로 올린다")
	void paidCompatibilityRejectionRollsBackStatus() {
		willThrow(rejection()).given(manseInterpretationService)
			.analyzeCompatibilityWithSubcategory(anyString(), any(), anyString(), any(),
				anyLong(), anyLong(), anyString(), any(), any());

		assertThatThrownBy(() -> controller.analyzeCompatibility(
			SUBCATEGORY_ID, paidCompatibilityRequest(), USERNAME))
			.isInstanceOf(TaskRejectedException.class);

		InOrder inOrder = inOrder(resultService);
		inOrder.verify(resultService).updateCompatibilityStatusToProcessing(PAYMENT_ID);
		inOrder.verify(resultService).rollbackCompatibilityStatusByPaymentId(PAYMENT_ID);
	}

	@Test
	@DisplayName("무료 단일 해석 제출이 거부되면 상태를 되돌리고 거부 예외를 그대로 올린다")
	void freeSingleRejectionRollsBackStatus() {
		givenFreeOrder();
		willThrow(rejection()).given(manseInterpretationService)
			.interpretFree(anyString(), any(), anyString(), anyLong(), anyLong());

		assertThatThrownBy(() -> controller.interpretFree(
			FREE_SUBCATEGORY_ID, singleRequest(), USERNAME))
			.isInstanceOf(TaskRejectedException.class);

		InOrder inOrder = inOrder(resultService);
		inOrder.verify(resultService).updateStatusToProcessing(FREE_PAYMENT_ID);
		inOrder.verify(resultService).rollbackStatusByPaymentId(FREE_PAYMENT_ID);
	}

	@Test
	@DisplayName("무료 궁합 제출이 거부되면 궁합 상태를 되돌리고 거부 예외를 그대로 올린다")
	void freeCompatibilityRejectionRollsBackStatus() {
		givenFreeOrder();
		willThrow(rejection()).given(manseInterpretationService)
			.analyzeCompatibilityFree(anyString(), any(), anyString(), any(),
				anyLong(), anyLong(), anyString());

		assertThatThrownBy(() -> controller.analyzeCompatibilityFree(
			FREE_SUBCATEGORY_ID, freeCompatibilityRequest(), USERNAME))
			.isInstanceOf(TaskRejectedException.class);

		InOrder inOrder = inOrder(resultService);
		inOrder.verify(resultService).updateCompatibilityStatusToProcessing(FREE_PAYMENT_ID);
		inOrder.verify(resultService).rollbackCompatibilityStatusByPaymentId(FREE_PAYMENT_ID);
	}

	@Test
	@DisplayName("제출이 정상이면 상태를 되돌리지 않는다")
	void successfulSubmissionDoesNotRollBack() {
		controller.interpret(SUBCATEGORY_ID, singleRequest(), USERNAME);

		verify(resultService).updateStatusToProcessing(PAYMENT_ID);
		verify(resultService, never()).rollbackStatusByPaymentId(anyLong());
	}
}
