package com.mansereok.server.domain.interpret.dto.response;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class SajuHistoryResponseDto {

	private Long resultId;
	private String resultType;
	private String productName;
	private LocalDateTime createdAt;
	private ResultStatus status;
	private Long paymentId;

	// 단일 사주(Result)용 생성자
	public SajuHistoryResponseDto(Result result) {
		this.resultId = result.getId();
		this.resultType = "SAJU";
		this.productName = result.getProductName();
		this.createdAt = result.getCreatedAt();
		this.status = result.getStatus();
		this.paymentId = result.getPaymentId();
	}

	// 궁합(CompatibilityResult)용 생성자
	public SajuHistoryResponseDto(CompatibilityResult result) {
		this.resultId = result.getId();
		this.resultType = "COMPATIBILITY";
		this.productName = result.getProductName();
		this.createdAt = result.getCreatedAt();
		this.status = result.getStatus();
		this.paymentId = result.getPaymentId();
	}
}
