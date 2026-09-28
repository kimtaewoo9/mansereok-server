package com.mansereok.server.domain.interpret.exception;

import com.mansereok.server.domain.interpret.entity.ResultStatus;
import java.time.LocalDateTime;

/**
 * 해석 실행이 결과를 쓰려는데, 그 실행이 시작한 해석이 더는 이 결과의 해석이 아닐 때 던진다.
 *
 * <p>해석 중에 오래 멈춘 결과는 StaleProcessingResultScheduler 가 정보 입력 대기로 되돌리고, 사용자는 같은 결제로 해석을 다시 시작할
 * 수 있다. 그 뒤에 먼저 시작한 해석이 늦게 끝나 결과를 쓰면, 다시 시작한 해석의 입력 정보에 먼저 시작한 해석의 본문이 붙는다. 그래서
 * 해석 실행은 결과를 쓸 때마다 자기가 해석을 시작한 시각이 결과에 그대로 남아 있는지 보고, 다르면 이 예외로 쓰기를 멈춘다.
 *
 * <p>InterpretationPipeline 은 이 예외를 받으면 결과를 쓰지도 되돌리지도 않고 로그만 남긴다. 결과는 이제 다른 실행이나 사용자의 것이다.
 */
public class InterpretationRunOutdatedException extends RuntimeException {

	private final Long paymentId;
	private final LocalDateTime startedAt;

	public InterpretationRunOutdatedException(Long paymentId, LocalDateTime startedAt, ResultStatus currentStatus,
		LocalDateTime currentUpdatedAt) {
		super(String.format("해석을 시작한 뒤 결과가 되돌려졌거나 다른 요청이 해석을 다시 시작해 이 해석은 결과를 쓰지 않는다. "
				+ "paymentId=%s, 이 해석을 시작한 시각=%s, 지금 상태=%s, 지금 updated_at=%s",
			paymentId, startedAt, currentStatus, currentUpdatedAt));
		this.paymentId = paymentId;
		this.startedAt = startedAt;
	}

	public Long getPaymentId() {
		return paymentId;
	}

	/** 이 해석 실행이 해석을 시작한 시각. ResultService.startProcessing 이 돌려준 값이다. */
	public LocalDateTime getStartedAt() {
		return startedAt;
	}
}
