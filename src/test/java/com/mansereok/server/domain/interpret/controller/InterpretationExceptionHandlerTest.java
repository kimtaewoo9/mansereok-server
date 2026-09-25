package com.mansereok.server.domain.interpret.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mansereok.server.domain.interpret.exception.InterpretationAlreadyStartedException;
import com.mansereok.server.domain.interpret.prompt.PromptFixtures;
import com.mansereok.server.domain.interpret.service.ManseCalculationService;
import com.mansereok.server.domain.interpret.service.ManseInterpretationService;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.payment.service.PaymentService;
import com.mansereok.server.global.exception.ErrorResponse;
import com.mansereok.server.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 이미 해석 중이거나 완료된 결제로 해석을 다시 요청하면 409 INTERPRETATION_ALREADY_STARTED 로 내려가는지 확인한다.
 *
 * <p>처리기 메서드를 직접 부르는 테스트는 응답 내용을, MockMvc 테스트는 실제 요청에서 GlobalExceptionHandler 의 500 처리기보다 이
 * 처리기가 먼저 걸리는지를 본다. MockMvc 에는 GlobalExceptionHandler 를 먼저 넣어, 순서를 정하는 {@code @Order} 가 없으면 500 이
 * 나오게 해 두었다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InterpretationExceptionHandler")
class InterpretationExceptionHandlerTest {

	private static final String USER_MESSAGE = "이미 진행 중이거나 완료된 해석입니다. 결과 화면에서 확인해 주세요.";

	@Test
	@DisplayName("해석 재시작 거절은 409 INTERPRETATION_ALREADY_STARTED 로 내려가고 응답 문구에 결제 ID 를 싣지 않는다")
	void mapsAlreadyStartedTo409() {
		// given
		InterpretationExceptionHandler handler = new InterpretationExceptionHandler();

		// when
		ResponseEntity<ErrorResponse> response = handler.handleInterpretationAlreadyStarted(
			new InterpretationAlreadyStartedException(987654L));

		// then
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody())
			.extracting(ErrorResponse::getStatus, ErrorResponse::getErrorCode, ErrorResponse::getMessage)
			.containsExactly(409, "INTERPRETATION_ALREADY_STARTED", USER_MESSAGE);
		assertThat(response.getBody().getMessage()).doesNotContain("987654");
	}

	@Nested
	@DisplayName("해석 API 로 실제 요청을 보내면")
	class ThroughController {

		private static final Long PAYMENT_ID = 100L;
		private static final String SINGLE_BODY = """
			{"name": "홍길동", "solarDate": "1990-01-01", "solarTime": "12:00:00", "gender": "MALE",
			 "isLunar": false, "paymentId": 100}
			""";

		@Mock
		private ManseCalculationService manseCalculationService;
		@Mock
		private ManseInterpretationService manseInterpretationService;
		@Mock
		private PaymentService paymentService;
		@Mock
		private ResultService resultService;

		private MockMvc mockMvc;

		@BeforeEach
		void setUp() {
			ManseryeokController controller = new ManseryeokController(manseCalculationService,
				manseInterpretationService, paymentService, resultService);
			// GlobalExceptionHandler 를 먼저 넣는다. 순서가 등록 순서대로면 모든 예외를 받는 500 처리기가 먼저 걸린다.
			mockMvc = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalExceptionHandler(), new InterpretationExceptionHandler())
				.build();
		}

		@Test
		@DisplayName("해석 시작이 거절되면 GlobalExceptionHandler 가 함께 있어도 409 INTERPRETATION_ALREADY_STARTED 를 받는다")
		void alreadyStartedIsConflict() throws Exception {
			// given
			given(manseCalculationService.calculate(any())).willReturn(PromptFixtures.person1());
			willThrow(new InterpretationAlreadyStartedException(PAYMENT_ID))
				.given(resultService).startProcessing(PAYMENT_ID);

			// when & then
			mockMvc.perform(post("/api/v1/manseryeok/interpret/1")
					.contentType(MediaType.APPLICATION_JSON)
					.content(SINGLE_BODY))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.errorCode").value("INTERPRETATION_ALREADY_STARTED"))
				.andExpect(jsonPath("$.message").value(USER_MESSAGE));
		}

		@Test
		@DisplayName("이 처리기가 받지 않는 예외는 GlobalExceptionHandler 로 넘어가 원래 응답(400 INVALID_INPUT)을 받는다")
		void otherExceptionsFallThroughToGlobalHandler() throws Exception {
			// given
			given(manseCalculationService.calculate(any()))
				.willThrow(new IllegalArgumentException("지원하지 않는 성별 값입니다"));

			// when & then
			mockMvc.perform(post("/api/v1/manseryeok/interpret/1")
					.contentType(MediaType.APPLICATION_JSON)
					.content(SINGLE_BODY))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));
		}
	}
}
