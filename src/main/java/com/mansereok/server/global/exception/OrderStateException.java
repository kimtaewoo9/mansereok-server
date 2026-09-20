package com.mansereok.server.global.exception;

/**
 * 주문·결제의 상태 전이 규칙에 어긋나는 전이를 시도했을 때 던진다.
 *
 * <p>PaymentException 을 상속하므로 GlobalExceptionHandler 에서 400 Bad Request 로 응답된다.
 */
public class OrderStateException extends PaymentException {

	public OrderStateException(String message) {
		super(message);
	}
}
