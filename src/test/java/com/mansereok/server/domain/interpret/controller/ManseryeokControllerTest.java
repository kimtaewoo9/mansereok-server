package com.mansereok.server.domain.interpret.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.interpret.dto.request.CompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseCompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseInterpretationRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCalculationRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCreateRequest;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.exception.InterpretationAlreadyStartedException;
import com.mansereok.server.domain.interpret.prompt.PromptFixtures;
import com.mansereok.server.domain.interpret.service.ManseCalculationService;
import com.mansereok.server.domain.interpret.service.ManseInterpretationService;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.service.PaymentService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 해석 컨트롤러의 네 엔드포인트(유료 단일, 유료 궁합, 무료 단일, 무료 궁합)가 지키는 약속을 네 묶음으로 확인한다.
 *
 * <ul>
 *   <li>상품 확인: 경로의 상품 번호가 그 엔드포인트가 맡는 종류가 아니면 계산·주문·상태 변경 전에 400 용 예외로 끝난다.</li>
 *   <li>실패 순서: 입력 때문에 실패할 수 있는 입력 변환과 만세력 계산이 되돌릴 수 없는 쓰기(해석 시작 표시, 0원 주문 생성)보다
 *   먼저다. 계산이 실패하면 쓰기는 한 번도 불리지 않는다.</li>
 *   <li>인자 전달: 결제 ID 와 상품 ID(둘 다 Long), 두 사람의 작품명(둘 다 String), 음력·윤달 여부(둘 다 Boolean)가 제자리로
 *   넘어간다. 서로 다른 값을 넣고 eq 로 고정해, 자리가 바뀌면 실패한다.</li>
 *   <li>제출 거부와 중복 시작: 스레드 풀이 제출을 거부하면 PROCESSING 으로 바꿔 둔 상태를 되돌리고 거부 예외를 그대로 올린다. 이미
 *   해석 중이거나 완료된 결제면 해석을 제출하지도, 남의 상태를 되돌리지도 않는다.</li>
 * </ul>
 *
 * <p>해석 시작 표시가 돌려준 시각은 비동기 해석과 되돌리기에 그대로 넘어가야 한다. 제출 거부 스텁은 그 시각을 eq 로 걸어, 다른 값을
 * 넘기면 스텁이 맞지 않아 거부 예외가 나지 않고 테스트가 실패한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ManseryeokController")
class ManseryeokControllerTest {

	private static final String USERNAME = "user-1";
	private static final Long PAYMENT_ID = 100L;
	private static final Long FREE_PAYMENT_ID = 200L;
	// 엔드포인트마다 맡는 상품 종류가 다르다. 결제 ID 와 겹치지 않는 번호를 골라 두 Long 이 바뀌면 드러나게 한다.
	private static final Long SAJU_SUBCATEGORY_ID = 1L;
	private static final Long COMPATIBILITY_SUBCATEGORY_ID = 7L;
	private static final Long FREE_FORTUNE_SUBCATEGORY_ID = 101L;
	private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 9, 26, 9, 0);

	@Mock
	private ManseCalculationService manseCalculationService;
	@Mock
	private ManseInterpretationService manseInterpretationService;
	@Mock
	private PaymentService paymentService;
	@Mock
	private ResultService resultService;

	private final ManseryeokCalculationResponse person1Manse = PromptFixtures.person1();
	private final ManseryeokCalculationResponse person2Manse = PromptFixtures.person2();

	private ManseryeokController controller;

	@BeforeEach
	void setUp() {
		controller = new ManseryeokController(manseCalculationService, manseInterpretationService,
			paymentService, resultService);
	}

	@Nested
	@DisplayName("상품 확인: 경로의 상품 번호가 엔드포인트와 맞지 않으면")
	class ProductCheck {

		@ParameterizedTest(name = "[{index}] 상품 {0}")
		@DisplayName("유료 단일은 궁합 상품이나 목록에 없는 번호를 400 용 예외로 끝내고 아무것도 부르지 않는다")
		@ValueSource(longs = {4, 12, 999})
		void paidSingleRejectsProduct(long subcategoryId) {
			assertThatThrownBy(() -> controller.interpret(subcategoryId, singleRequest(), USERNAME))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("지원하지 않는 카테고리입니다: %d", subcategoryId);
			verifyNoInteractions(manseCalculationService, paymentService, resultService, manseInterpretationService);
		}

		@ParameterizedTest(name = "[{index}] 상품 {0}")
		@DisplayName("유료 궁합은 사주·무료 운세 상품이나 목록에 없는 번호를 400 용 예외로 끝내고 아무것도 부르지 않는다")
		@ValueSource(longs = {1, 101, 999})
		void paidCompatibilityRejectsProduct(long subcategoryId) {
			assertThatThrownBy(() -> controller.analyzeCompatibility(
				subcategoryId, paidCompatibilityRequest(), USERNAME))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("지원하지 않는 카테고리입니다: %d", subcategoryId);
			verifyNoInteractions(manseCalculationService, paymentService, resultService, manseInterpretationService);
		}

		@ParameterizedTest(name = "[{index}] 상품 {0}")
		@DisplayName("무료 단일은 무료 운세 상품이 아니면 400 용 예외로 끝내고 0원 주문을 만들지 않는다")
		@ValueSource(longs = {1, 4, 999})
		void freeSingleRejectsProduct(long subcategoryId) {
			assertThatThrownBy(() -> controller.interpretFree(subcategoryId, singleRequest(), USERNAME))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("지원하지 않는 카테고리입니다: %d", subcategoryId);
			verifyNoInteractions(manseCalculationService, paymentService, resultService, manseInterpretationService);
		}

		@ParameterizedTest(name = "[{index}] 상품 {0}")
		@DisplayName("무료 궁합은 궁합 상품이 아니면 400 용 예외로 끝내고 0원 주문을 만들지 않는다")
		@ValueSource(longs = {1, 101, 999})
		void freeCompatibilityRejectsProduct(long subcategoryId) {
			assertThatThrownBy(() -> controller.analyzeCompatibilityFree(
				subcategoryId, freeCompatibilityRequest(), USERNAME))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("지원하지 않는 카테고리입니다: %d", subcategoryId);
			verifyNoInteractions(manseCalculationService, paymentService, resultService, manseInterpretationService);
		}
	}

	@Nested
	@DisplayName("실패 순서: 입력 변환과 만세력 계산은 되돌릴 수 없는 쓰기보다 먼저다")
	class FailureOrder {

		@Test
		@DisplayName("유료 단일은 계산이 실패하면 계산 예외를 그대로 올리고 결과 상태를 건드리지 않는다")
		void paidSingleLeavesStatusWhenCalculationFails() {
			// given
			given(manseCalculationService.calculate(any()))
				.willThrow(new IllegalArgumentException("지원하지 않는 성별 값입니다"));

			// when & then
			assertThatThrownBy(() -> controller.interpret(SAJU_SUBCATEGORY_ID, singleRequest(), USERNAME))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("지원하지 않는 성별 값입니다");
			verifyNoInteractions(resultService, manseInterpretationService);
		}

		@Test
		@DisplayName("유료 궁합은 두 번째 사람 계산만 실패해도 결과 상태를 건드리지 않는다")
		void paidCompatibilityLeavesStatusWhenSecondPersonFails() {
			// given
			given(manseCalculationService.calculate(any()))
				.willReturn(person1Manse)
				.willThrow(new IllegalArgumentException("지원하지 않는 성별 값입니다"));

			// when & then
			assertThatThrownBy(() -> controller.analyzeCompatibility(
				COMPATIBILITY_SUBCATEGORY_ID, paidCompatibilityRequest(), USERNAME))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("지원하지 않는 성별 값입니다");
			verifyNoInteractions(resultService, manseInterpretationService);
		}

		@Test
		@DisplayName("무료 단일은 계산이 실패하면 0원 주문을 만들지 않고 결과 상태도 건드리지 않는다")
		void freeSingleCreatesNoOrderWhenCalculationFails() {
			// given
			given(manseCalculationService.calculate(any()))
				.willThrow(new RuntimeException("해당 양력 날짜의 만세력 데이터를 찾을 수 없습니다."));

			// when & then
			assertThatThrownBy(() -> controller.interpretFree(FREE_FORTUNE_SUBCATEGORY_ID, singleRequest(), USERNAME))
				.isInstanceOf(RuntimeException.class)
				.hasMessage("해당 양력 날짜의 만세력 데이터를 찾을 수 없습니다.");
			verifyNoInteractions(paymentService, resultService, manseInterpretationService);
		}

		@Test
		@DisplayName("무료 궁합은 두 번째 사람 계산만 실패해도 0원 주문을 만들지 않는다")
		void freeCompatibilityCreatesNoOrderWhenSecondPersonFails() {
			// given
			given(manseCalculationService.calculate(any()))
				.willReturn(person1Manse)
				.willThrow(new IllegalArgumentException("음력 날짜와 윤달 여부에 맞는 만세력 데이터를 찾을 수 없습니다."));

			// when & then
			assertThatThrownBy(() -> controller.analyzeCompatibilityFree(
				COMPATIBILITY_SUBCATEGORY_ID, freeCompatibilityRequest(), USERNAME))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("음력 날짜와 윤달 여부에 맞는 만세력 데이터를 찾을 수 없습니다.");
			verifyNoInteractions(paymentService, resultService, manseInterpretationService);
		}

		@Test
		@DisplayName("무료 궁합은 두 번째 사람의 생년월일이 없으면 입력 변환에서 400 용 예외로 끝나고 0원 주문을 만들지 않는다")
		void freeCompatibilityCreatesNoOrderWhenBirthdayIsMissing() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse);
			CompatibilityAnalysisRequest request = freeCompatibilityRequest();
			request.getPerson2().setBirthday(null);

			// when & then
			assertThatThrownBy(() -> controller.analyzeCompatibilityFree(
				COMPATIBILITY_SUBCATEGORY_ID, request, USERNAME))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("생년월일(birthday)은 필수입니다.");
			verifyNoInteractions(paymentService, resultService, manseInterpretationService);
		}

		@Test
		@DisplayName("유료 단일이 정상이면 계산, 해석 시작 표시, 해석 제출 순서로 부른다")
		void paidSingleCalculatesBeforeStarting() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse);
			given(resultService.startProcessing(PAYMENT_ID)).willReturn(STARTED_AT);

			// when
			controller.interpret(SAJU_SUBCATEGORY_ID, singleRequest(), USERNAME);

			// then
			InOrder inOrder = inOrder(manseCalculationService, resultService, manseInterpretationService);
			inOrder.verify(manseCalculationService).calculate(any());
			inOrder.verify(resultService).startProcessing(PAYMENT_ID);
			inOrder.verify(manseInterpretationService).interpret(any(), any(), any(), any(), any(), any(), any());
		}

		@Test
		@DisplayName("무료 단일이 정상이면 계산, 0원 주문 생성, 해석 시작 표시, 해석 제출 순서로 부른다")
		void freeSingleCalculatesBeforeCreatingOrder() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse);
			givenFreeOrder(FREE_FORTUNE_SUBCATEGORY_ID);
			given(resultService.startProcessing(FREE_PAYMENT_ID)).willReturn(STARTED_AT);

			// when
			controller.interpretFree(FREE_FORTUNE_SUBCATEGORY_ID, singleRequest(), USERNAME);

			// then
			InOrder inOrder = inOrder(manseCalculationService, paymentService, resultService,
				manseInterpretationService);
			inOrder.verify(manseCalculationService).calculate(any());
			inOrder.verify(paymentService).createFreeOrder(USERNAME, FREE_FORTUNE_SUBCATEGORY_ID);
			inOrder.verify(resultService).startProcessing(FREE_PAYMENT_ID);
			inOrder.verify(manseInterpretationService).interpretFree(any(), any(), any(), any(), any(), any());
		}

		@Test
		@DisplayName("무료 궁합이 정상이면 두 사람 계산을 모두 마친 뒤 0원 주문 생성, 해석 시작 표시, 해석 제출 순서로 부른다")
		void freeCompatibilityCalculatesBothBeforeCreatingOrder() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse, person2Manse);
			givenFreeOrder(COMPATIBILITY_SUBCATEGORY_ID);
			given(resultService.startCompatibilityProcessing(FREE_PAYMENT_ID)).willReturn(STARTED_AT);

			// when
			controller.analyzeCompatibilityFree(COMPATIBILITY_SUBCATEGORY_ID, freeCompatibilityRequest(), USERNAME);

			// then
			InOrder inOrder = inOrder(manseCalculationService, paymentService, resultService,
				manseInterpretationService);
			inOrder.verify(manseCalculationService, times(2)).calculate(any());
			inOrder.verify(paymentService).createFreeOrder(USERNAME, COMPATIBILITY_SUBCATEGORY_ID);
			inOrder.verify(resultService).startCompatibilityProcessing(FREE_PAYMENT_ID);
			inOrder.verify(manseInterpretationService).analyzeCompatibilityFree(any(), any(), any(), any(), any(),
				any(), any(), any());
		}
	}

	@Nested
	@DisplayName("인자 전달: 같은 타입이 나란히 오는 인자가 제자리로 넘어간다")
	class ArgumentPassing {

		@Test
		@DisplayName("유료 단일은 음력·윤달 여부를 계산에, 결제 ID·상품 ID·작품명을 해석에 제자리로 넘긴다")
		void paidSinglePassesArgumentsInPlace() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse);
			given(resultService.startProcessing(PAYMENT_ID)).willReturn(STARTED_AT);
			ManseInterpretationRequest request = singleRequest();
			request.setIsLunar(true);
			request.setLeapMonth(false);

			// when
			controller.interpret(SAJU_SUBCATEGORY_ID, request, USERNAME);

			// then
			ArgumentCaptor<ManseryeokCalculationRequest> calculated =
				ArgumentCaptor.forClass(ManseryeokCalculationRequest.class);
			verify(manseCalculationService).calculate(calculated.capture());
			assertThat(calculated.getValue())
				.extracting(ManseryeokCalculationRequest::getName, ManseryeokCalculationRequest::getSolarDate,
					ManseryeokCalculationRequest::getIsLunar, ManseryeokCalculationRequest::getLeapMonth)
				.containsExactly("홍길동", LocalDate.of(1990, 1, 1), true, false);
			verify(manseInterpretationService).interpret(eq("홍길동"), same(person1Manse), eq(USERNAME),
				eq(SAJU_SUBCATEGORY_ID), eq(PAYMENT_ID), eq(STARTED_AT), eq("슬램덩크"));
		}

		@Test
		@DisplayName("유료 단일 경로로 무료 운세 상품(101)을 요청하면 무료 해석만 부르고 유료 해석은 부르지 않는다")
		void paidSingleSendsFreeFortuneProductToFreeInterpretation() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse);
			given(resultService.startProcessing(PAYMENT_ID)).willReturn(STARTED_AT);

			// when
			controller.interpret(FREE_FORTUNE_SUBCATEGORY_ID, singleRequest(), USERNAME);

			// then
			verify(manseInterpretationService).interpretFree(eq("홍길동"), same(person1Manse), eq(USERNAME),
				eq(FREE_FORTUNE_SUBCATEGORY_ID), eq(PAYMENT_ID), eq(STARTED_AT));
			verify(manseInterpretationService, never()).interpret(any(), any(), any(), any(), any(), any(), any());
		}

		@Test
		@DisplayName("유료 궁합은 두 사람의 계산 결과·이름·작품명을 순서대로, 결제 ID 와 상품 ID 를 제자리로 넘긴다")
		void paidCompatibilityPassesArgumentsInPlace() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse, person2Manse);
			given(resultService.startCompatibilityProcessing(PAYMENT_ID)).willReturn(STARTED_AT);
			ManseCompatibilityAnalysisRequest request = paidCompatibilityRequest();
			request.getPerson1().setIsLunar(true);
			request.getPerson1().setLeapMonth(false);
			request.getPerson2().setIsLunar(false);
			request.getPerson2().setLeapMonth(true);

			// when
			controller.analyzeCompatibility(COMPATIBILITY_SUBCATEGORY_ID, request, USERNAME);

			// then
			ArgumentCaptor<ManseryeokCalculationRequest> calculated =
				ArgumentCaptor.forClass(ManseryeokCalculationRequest.class);
			verify(manseCalculationService, times(2)).calculate(calculated.capture());
			assertThat(calculated.getAllValues())
				.extracting(ManseryeokCalculationRequest::getName, ManseryeokCalculationRequest::getIsLunar,
					ManseryeokCalculationRequest::getLeapMonth)
				.containsExactly(
					tuple("홍길동", true, false),
					tuple("김영희", false, true));
			verify(manseInterpretationService).analyzeCompatibilityWithSubcategory(
				eq("홍길동"), same(person1Manse), eq("김영희"), same(person2Manse),
				eq(COMPATIBILITY_SUBCATEGORY_ID), eq(PAYMENT_ID), eq(STARTED_AT), eq(USERNAME),
				eq("슬램덩크"), eq("원피스"));
		}

		@Test
		@DisplayName("무료 단일은 요청의 결제 ID 대신 새로 만든 0원 결제의 ID 를 해석에 넘긴다")
		void freeSinglePassesNewFreePaymentId() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse);
			givenFreeOrder(FREE_FORTUNE_SUBCATEGORY_ID);
			given(resultService.startProcessing(FREE_PAYMENT_ID)).willReturn(STARTED_AT);

			// when
			controller.interpretFree(FREE_FORTUNE_SUBCATEGORY_ID, singleRequest(), USERNAME);

			// then
			verify(manseInterpretationService).interpretFree(eq("홍길동"), same(person1Manse), eq(USERNAME),
				eq(FREE_FORTUNE_SUBCATEGORY_ID), eq(FREE_PAYMENT_ID), eq(STARTED_AT));
		}

		@Test
		@DisplayName("무료 궁합은 달력 L 과 윤달 여부를 계산에, 두 사람과 0원 결제 ID·상품 ID 를 해석에 제자리로 넘긴다")
		void freeCompatibilityPassesArgumentsInPlace() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse, person2Manse);
			givenFreeOrder(COMPATIBILITY_SUBCATEGORY_ID);
			given(resultService.startCompatibilityProcessing(FREE_PAYMENT_ID)).willReturn(STARTED_AT);
			CompatibilityAnalysisRequest request = freeCompatibilityRequest();
			request.getPerson1().setCalendar("L");
			request.getPerson1().setLeapMonth(true);

			// when
			controller.analyzeCompatibilityFree(COMPATIBILITY_SUBCATEGORY_ID, request, USERNAME);

			// then
			ArgumentCaptor<ManseryeokCalculationRequest> calculated =
				ArgumentCaptor.forClass(ManseryeokCalculationRequest.class);
			verify(manseCalculationService, times(2)).calculate(calculated.capture());
			assertThat(calculated.getAllValues())
				.extracting(ManseryeokCalculationRequest::getName, ManseryeokCalculationRequest::getIsLunar,
					ManseryeokCalculationRequest::getLeapMonth)
				.containsExactly(
					tuple("홍길동", true, true),
					tuple("김영희", false, null));
			verify(manseInterpretationService).analyzeCompatibilityFree(
				eq("홍길동"), same(person1Manse), eq("김영희"), same(person2Manse),
				eq(COMPATIBILITY_SUBCATEGORY_ID), eq(FREE_PAYMENT_ID), eq(STARTED_AT), eq(USERNAME));
		}
	}

	@Nested
	@DisplayName("제출 거부와 중복 시작")
	class RejectionAndAlreadyStarted {

		@Test
		@DisplayName("유료 단일 해석 제출이 거부되면 상태를 되돌리고 거부 예외를 그대로 올린다")
		void paidSingleRejectionRollsBackStatus() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse);
			given(resultService.startProcessing(PAYMENT_ID)).willReturn(STARTED_AT);
			willThrow(rejection()).given(manseInterpretationService)
				.interpret(any(), any(), any(), any(), any(), eq(STARTED_AT), any());

			// when & then
			assertThatThrownBy(() -> controller.interpret(SAJU_SUBCATEGORY_ID, singleRequest(), USERNAME))
				.isInstanceOf(TaskRejectedException.class);
			InOrder inOrder = inOrder(resultService);
			inOrder.verify(resultService).startProcessing(PAYMENT_ID);
			inOrder.verify(resultService).rollbackStatusByPaymentId(PAYMENT_ID, STARTED_AT);
		}

		@Test
		@DisplayName("유료 궁합 제출이 거부되면 궁합 상태를 되돌리고 거부 예외를 그대로 올린다")
		void paidCompatibilityRejectionRollsBackStatus() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse, person2Manse);
			given(resultService.startCompatibilityProcessing(PAYMENT_ID)).willReturn(STARTED_AT);
			willThrow(rejection()).given(manseInterpretationService)
				.analyzeCompatibilityWithSubcategory(any(), any(), any(), any(), any(), any(), eq(STARTED_AT), any(),
					any(), any());

			// when & then
			assertThatThrownBy(() -> controller.analyzeCompatibility(
				COMPATIBILITY_SUBCATEGORY_ID, paidCompatibilityRequest(), USERNAME))
				.isInstanceOf(TaskRejectedException.class);
			InOrder inOrder = inOrder(resultService);
			inOrder.verify(resultService).startCompatibilityProcessing(PAYMENT_ID);
			inOrder.verify(resultService).rollbackCompatibilityStatusByPaymentId(PAYMENT_ID, STARTED_AT);
		}

		@Test
		@DisplayName("무료 단일 해석 제출이 거부되면 상태를 되돌리고 거부 예외를 그대로 올린다")
		void freeSingleRejectionRollsBackStatus() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse);
			givenFreeOrder(FREE_FORTUNE_SUBCATEGORY_ID);
			given(resultService.startProcessing(FREE_PAYMENT_ID)).willReturn(STARTED_AT);
			willThrow(rejection()).given(manseInterpretationService)
				.interpretFree(any(), any(), any(), any(), any(), eq(STARTED_AT));

			// when & then
			assertThatThrownBy(() -> controller.interpretFree(
				FREE_FORTUNE_SUBCATEGORY_ID, singleRequest(), USERNAME))
				.isInstanceOf(TaskRejectedException.class);
			InOrder inOrder = inOrder(resultService);
			inOrder.verify(resultService).startProcessing(FREE_PAYMENT_ID);
			inOrder.verify(resultService).rollbackStatusByPaymentId(FREE_PAYMENT_ID, STARTED_AT);
		}

		@Test
		@DisplayName("무료 궁합 제출이 거부되면 궁합 상태를 되돌리고 거부 예외를 그대로 올린다")
		void freeCompatibilityRejectionRollsBackStatus() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse, person2Manse);
			givenFreeOrder(COMPATIBILITY_SUBCATEGORY_ID);
			given(resultService.startCompatibilityProcessing(FREE_PAYMENT_ID)).willReturn(STARTED_AT);
			willThrow(rejection()).given(manseInterpretationService)
				.analyzeCompatibilityFree(any(), any(), any(), any(), any(), any(), eq(STARTED_AT), any());

			// when & then
			assertThatThrownBy(() -> controller.analyzeCompatibilityFree(
				COMPATIBILITY_SUBCATEGORY_ID, freeCompatibilityRequest(), USERNAME))
				.isInstanceOf(TaskRejectedException.class);
			InOrder inOrder = inOrder(resultService);
			inOrder.verify(resultService).startCompatibilityProcessing(FREE_PAYMENT_ID);
			inOrder.verify(resultService).rollbackCompatibilityStatusByPaymentId(FREE_PAYMENT_ID, STARTED_AT);
		}

		@Test
		@DisplayName("제출이 정상이면 해석을 시작한 시각을 비동기 해석에 넘기고 상태를 되돌리지 않는다")
		void successfulSubmissionDoesNotRollBack() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse);
			given(resultService.startProcessing(PAYMENT_ID)).willReturn(STARTED_AT);

			// when
			controller.interpret(SAJU_SUBCATEGORY_ID, singleRequest(), USERNAME);

			// then
			verify(manseInterpretationService).interpret(eq("홍길동"), any(), eq(USERNAME), eq(SAJU_SUBCATEGORY_ID),
				eq(PAYMENT_ID), eq(STARTED_AT), any());
			verify(resultService, never()).rollbackStatusByPaymentId(anyLong(), any());
		}

		@Test
		@DisplayName("이미 해석 중이거나 완료된 결제면 유료 단일은 409 용 예외를 그대로 올리고 해석을 제출하지도 상태를 되돌리지도 않는다")
		void paidSingleAlreadyStartedDoesNotSubmitOrRollBack() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse);
			willThrow(new InterpretationAlreadyStartedException(PAYMENT_ID))
				.given(resultService).startProcessing(PAYMENT_ID);

			// when & then
			assertThatThrownBy(() -> controller.interpret(SAJU_SUBCATEGORY_ID, singleRequest(), USERNAME))
				.isInstanceOf(InterpretationAlreadyStartedException.class);
			verifyNoInteractions(manseInterpretationService);
			verify(resultService, never()).rollbackStatusByPaymentId(anyLong(), any());
		}

		@Test
		@DisplayName("이미 해석 중이거나 완료된 결제면 유료 궁합은 409 용 예외를 그대로 올리고 해석을 제출하지도 상태를 되돌리지도 않는다")
		void paidCompatibilityAlreadyStartedDoesNotSubmitOrRollBack() {
			// given
			given(manseCalculationService.calculate(any())).willReturn(person1Manse, person2Manse);
			willThrow(new InterpretationAlreadyStartedException(PAYMENT_ID))
				.given(resultService).startCompatibilityProcessing(PAYMENT_ID);

			// when & then
			assertThatThrownBy(() -> controller.analyzeCompatibility(
				COMPATIBILITY_SUBCATEGORY_ID, paidCompatibilityRequest(), USERNAME))
				.isInstanceOf(InterpretationAlreadyStartedException.class);
			verifyNoInteractions(manseInterpretationService);
			verify(resultService, never()).rollbackCompatibilityStatusByPaymentId(anyLong(), any());
		}
	}

	/**
	 * 0원 주문 생성을 스텁한다. 사용자 이름과 상품 번호를 정확한 값으로 걸어, 컨트롤러가 다른 값을 넘기면 strict stubs 가 실패시킨다.
	 */
	private void givenFreeOrder(Long subcategoryId) {
		Payment payment = Payment.create("pay_free_test", "free_test", 0L, PaymentStatus.PAID, 1L, 1L, subcategoryId);
		ReflectionTestUtils.setField(payment, "id", FREE_PAYMENT_ID);
		given(paymentService.createFreeOrder(USERNAME, subcategoryId)).willReturn(payment);
	}

	private ManseInterpretationRequest singleRequest() {
		ManseInterpretationRequest request = new ManseInterpretationRequest();
		request.setName("홍길동");
		request.setSolarDate(LocalDate.of(1990, 1, 1));
		request.setSolarTime(LocalTime.of(12, 0));
		request.setGender("MALE");
		request.setIsLunar(false);
		request.setSourceTitle("슬램덩크");
		request.setPaymentId(PAYMENT_ID);
		return request;
	}

	private ManseCompatibilityAnalysisRequest paidCompatibilityRequest() {
		ManseCompatibilityAnalysisRequest request = new ManseCompatibilityAnalysisRequest();
		request.setPerson1(person("홍길동", "슬램덩크"));
		request.setPerson2(person("김영희", "원피스"));
		request.setPaymentId(PAYMENT_ID);
		return request;
	}

	private ManseCompatibilityAnalysisRequest.PersonInfo person(String name, String sourceTitle) {
		ManseCompatibilityAnalysisRequest.PersonInfo person = new ManseCompatibilityAnalysisRequest.PersonInfo();
		person.setName(name);
		person.setSolarDate(LocalDate.of(1990, 1, 1));
		person.setSolarTime(LocalTime.of(12, 0));
		person.setGender("MALE");
		person.setIsLunar(false);
		person.setSourceTitle(sourceTitle);
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
}
