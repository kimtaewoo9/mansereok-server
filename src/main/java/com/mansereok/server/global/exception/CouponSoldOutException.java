package com.mansereok.server.global.exception;

/**
 * 선착순 쿠폰이 상한만큼 모두 발급돼 더 받을 수 없을 때 던진다.
 *
 * <p>이벤트가 열린 직후 사람이 몰리면 늦게 온 요청 대부분이 겪는 정상적인 거절이지 서버 오류가 아니다. PaymentException 을
 * 상속하므로 GlobalExceptionHandler 에서 400 PAYMENT_ERROR 로 응답되고, 로그도 스택 트레이스 없는 WARN 한 줄로 남는다.
 * 쿠폰 받기의 다른 거절(발급 기간 아님, 이미 받음)과 같은 응답 규칙이다.
 */
public class CouponSoldOutException extends PaymentException {

	public CouponSoldOutException() {
		super("선착순 마감되었습니다.");
	}
}
