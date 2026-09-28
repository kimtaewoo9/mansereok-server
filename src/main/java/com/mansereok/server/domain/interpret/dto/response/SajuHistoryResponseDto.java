package com.mansereok.server.domain.interpret.dto.response;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import java.time.LocalDateTime;
import lombok.Getter;

/**
 * 내 해석 이력 목록의 한 줄. 사주 결과와 궁합 결과를 한 목록에 섞어 보여 주며, resultType 으로 둘을 구분한다.
 */
@Getter
public class SajuHistoryResponseDto {

	/**
	 * 이력 한 줄이 어느 결과에서 왔는가. JSON 에는 상수 이름("SAJU", "COMPATIBILITY")으로 나가며 프론트가 이 값으로 목록을 거른다.
	 */
	public enum ResultType {
		SAJU,
		COMPATIBILITY
	}

	private final Long resultId;
	private final ResultType resultType;
	private final String productName;
	private final LocalDateTime createdAt;
	private final ResultStatus status;
	private final Long paymentId;

	private SajuHistoryResponseDto(Long resultId, ResultType resultType, String productName,
		LocalDateTime createdAt, ResultStatus status, Long paymentId) {
		this.resultId = resultId;
		this.resultType = resultType;
		this.productName = productName;
		this.createdAt = createdAt;
		this.status = status;
		this.paymentId = paymentId;
	}

	/**
	 * 사주 결과 한 건을 이력 한 줄로 만든다.
	 */
	public static SajuHistoryResponseDto fromSaju(Result result) {
		return new SajuHistoryResponseDto(result.getId(), ResultType.SAJU, result.getProductName(),
			result.getCreatedAt(), result.getStatus(), result.getPaymentId());
	}

	/**
	 * 궁합 결과 한 건을 이력 한 줄로 만든다.
	 */
	public static SajuHistoryResponseDto fromCompatibility(CompatibilityResult result) {
		return new SajuHistoryResponseDto(result.getId(), ResultType.COMPATIBILITY, result.getProductName(),
			result.getCreatedAt(), result.getStatus(), result.getPaymentId());
	}
}
