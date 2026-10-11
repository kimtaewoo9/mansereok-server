package com.mansereok.server.domain.payment.dto.request;

/**
 * 요청으로 받는 포트원 결제 ID 의 형식. 결제 완료·환불 요청이 같은 규칙을 쓰도록 한 곳에 둔다.
 *
 * <p>영문·숫자·'_'·'-' 로 된 1~100자만 받는다. 무료 주문의 결제 ID("free_" + 주문 번호)는 이 형식 안에 든다. 프론트가
 * 만드는 포트원 결제 ID 가 이 형식 안에 드는지는 배포 전에 확인한다.
 *
 * <p>포트원 조회·취소 주소는 결제 ID 를 URI 변수로 넘겨 인코딩하므로, 지금은 "pay_A#1" 도 잘리지 않고
 * /payments/pay_A%231 로 조회된다. 그래도 입구에서 '#'·'?'·'/' 처럼 URL 에서 뜻이 있는 문자를 막는다. 주소를 만드는
 * 방식이 바뀌어 인코딩이 빠지더라도 변형 ID 가 다른 결제를 가리키지 못하게 하려는 것이다.
 *
 * <p>검증은 문자열 전체를 보므로 끝에 줄바꿈이 붙은 "pay_A\n" 도 거부한다. 같은 규칙을 MySQL REGEXP 로 볼 때는 '$' 가
 * 끝 줄바꿈 앞에서도 맞으므로 끝 기호로 \z 를 쓴다. SQL 문자열 안에서는 역슬래시를 두 번 적어 '...{1,100}\\z' 로 쓴다.
 *
 * <p>필수 여부는 {@code @NotBlank} 가 아니라 {@code @NotNull} 로 검사한다. 빈 문자열과 공백은 이 형식이 이미 거부하는데
 * {@code @NotBlank} 까지 걸면 한 입력에 위반이 두 개 난다. 그러면 필드마다 메시지 하나만 담는 GlobalExceptionHandler 의
 * 응답 메시지가 어느 쪽이 될지 정해지지 않는다.
 */
final class PaymentIdFormat {

	static final String REGEXP = "^[A-Za-z0-9_-]{1,100}$";
	static final String MESSAGE = "결제 ID 형식이 올바르지 않습니다.";

	private PaymentIdFormat() {
	}
}
