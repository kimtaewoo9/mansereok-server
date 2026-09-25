package com.mansereok.server.global.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 결제·주문 비즈니스 예외가 400 PAYMENT_ERROR 로 나가는지 고정한다.
 *
 * <p>웹훅은 응답이 4xx 냐 5xx 냐에 따라 포트원이 같은 웹훅을 다시 보낼지가 갈리므로, 이 번역 자체가 계약이다. 처리 메서드를 직접
 * 부르면 {@code @ExceptionHandler} 선언이 빠져도 알아차리지 못한다. 그래서 운영과 같게 GlobalExceptionHandler 와
 * RequestErrorExceptionHandler 를 함께 등록한 MockMvc 로 요청을 보내, 스프링이 처리기를 고르는 과정까지 거치게 한다.
 */
class PaymentExceptionResponseTest {

	private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new PaymentErrorTestController())
		.setControllerAdvice(new GlobalExceptionHandler(), new RequestErrorExceptionHandler())
		.build();

	@ParameterizedTest(name = "[{index}] {0} → 400 PAYMENT_ERROR, {1}")
	@CsvSource(textBlock = """
		# 요청 경로,                    응답 메시지(예외 메시지 그대로)
		/test/payment-exception,        결제 금액이 일치하지 않습니다.
		# OrderStateException 은 PaymentException 을 상속한다.
		/test/order-state-exception,    '주문 상태를 PAID 에서 EXPIRED 로 바꿀 수 없습니다. orderId=1, merchantUid=order_1'
		# 원인을 품은 결제 예외. RequestErrorExceptionHandler 는 원인 체인까지 보지만 이 원인은 맡지 않는다.
		/test/payment-exception-cause,  이미 처리된 결제입니다.
		""")
	@DisplayName("결제 예외와 그 하위인 주문 상태 예외는 400 PAYMENT_ERROR 와 예외 메시지를 돌려준다")
	void paymentErrorIsBadRequest(String path, String expectedMessage) throws Exception {
		// when & then
		mockMvc.perform(get(path))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(400))
			.andExpect(jsonPath("$.errorCode").value("PAYMENT_ERROR"))
			.andExpect(jsonPath("$.message").value(expectedMessage));
	}

	/**
	 * 결제 서비스가 던지는 예외를 그대로 던지는 테스트 전용 컨트롤러.
	 */
	@RestController
	static class PaymentErrorTestController {

		// PaymentConfirmService 가 포트원 결제 금액과 주문 금액이 다를 때 던진다.
		@GetMapping("/test/payment-exception")
		public String paymentException() {
			throw new PaymentException("결제 금액이 일치하지 않습니다.");
		}

		// Order 가 상태 전이 규칙에 어긋나는 전이를 거부할 때 던진다.
		@GetMapping("/test/order-state-exception")
		public String orderStateException() {
			throw new OrderStateException(
				"주문 상태를 PAID 에서 EXPIRED 로 바꿀 수 없습니다. orderId=1, merchantUid=order_1");
		}

		// PaidOrderFinalizer 가 결제 저장의 UNIQUE 위반을 바꿔 던진다.
		@GetMapping("/test/payment-exception-cause")
		public String paymentExceptionWithCause() {
			throw new PaymentException("이미 처리된 결제입니다.",
				new DataIntegrityViolationException("Duplicate entry 'pay_1' for key 'payments.imp_uid'"));
		}
	}
}
