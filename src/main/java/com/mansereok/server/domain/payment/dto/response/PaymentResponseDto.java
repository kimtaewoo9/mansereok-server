package com.mansereok.server.domain.payment.dto.response;

import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.payment.entity.Payment;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class PaymentResponseDto {

	private String paymentId;
	private Long subCategoryId; // 서비스 id
	private Long amount;        // 결제 금액
	private String status;      // 결제 상태
	private LocalDateTime createdAt; // 결제 날짜

	private ResultStatus resultStatus;

	private boolean isRefundable;

	public static PaymentResponseDto create(Payment payment, ResultStatus resultStatus) {
		PaymentResponseDto responseDto = new PaymentResponseDto();

		responseDto.paymentId = payment.getImpUid();
		responseDto.subCategoryId = payment.getSubCategoryId();
		responseDto.amount = payment.getAmount();
		responseDto.status = payment.getStatus().toString();
		responseDto.createdAt = payment.getCreatedAt();
		responseDto.resultStatus = resultStatus;

		// 환불 API(PaymentRefundService)와 같은 판정을 써서 버튼이 보이는데 환불이 거절되는 일이 없게 한다.
		responseDto.isRefundable = payment.isRefundable(resultStatus);

		return responseDto;
	}
}
