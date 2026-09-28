package com.mansereok.server.domain.interpret.exception;

/**
 * 결제 한 건의 해석을 한 번 더 시작하려 할 때 던진다. 결과가 이미 해석 중(PROCESSING)이거나 완료(COMPLETED)라 다시 시작하지
 * 않는다. 버튼 연타, 네트워크 재전송, 완료된 결제로 다른 사람의 정보를 실어 다시 보내는 경우가 여기에 걸린다.
 *
 * <p>ManseryeokController 에서 올라오면 InterpretationExceptionHandler 가 409 로 내려 준다. 결제 ID 는 로그에만 남기고 응답
 * 문구에는 싣지 않는다.
 */
public class InterpretationAlreadyStartedException extends RuntimeException {

	private final Long paymentId;

	public InterpretationAlreadyStartedException(Long paymentId) {
		super("이미 해석 중이거나 완료된 결과라 해석을 다시 시작하지 않는다. paymentId=" + paymentId);
		this.paymentId = paymentId;
	}

	public Long getPaymentId() {
		return paymentId;
	}
}
