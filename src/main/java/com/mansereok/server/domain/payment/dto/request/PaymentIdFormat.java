package com.mansereok.server.domain.payment.dto.request;

/**
 * 요청으로 받는 포트원 결제 ID 의 형식. 결제 완료·환불 요청이 같은 규칙을 쓰도록 한 곳에 둔다.
 *
 * <p>영문·숫자·'_'·'-' 로 된 1~100자만 받는다. 포트원 V2 결제 ID 는 프론트가 만들고, 무료 주문의 결제 ID 는
 * "free_" + 주문 번호다. 둘 다 이 형식 안에 든다. '#'·'?'·'/' 처럼 URL 에서 뜻이 있는 문자를 허용하면
 * "pay_A#1" 같은 변형 ID 가 포트원에서는 pay_A 로 조회되어, 결제 한 건을 여러 주문에 붙이는 데 쓰일 수 있다.
 */
final class PaymentIdFormat {

	static final String REGEXP = "^[A-Za-z0-9_-]{1,100}$";
	static final String MESSAGE = "결제 ID 형식이 올바르지 않습니다.";

	private PaymentIdFormat() {
	}
}
