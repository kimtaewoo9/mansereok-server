package com.mansereok.server.domain.payment.dto.response;

import com.mansereok.server.domain.payment.entity.Payment;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class PaymentResponseDto {

	private Long subCategoryId; // 서비스 id
	private Long amount;        // 결제 금액
	private String status;      // 결제 상태
	private LocalDateTime createdAt; // 결제 날짜

	public static PaymentResponseDto create(Payment payment) {
		PaymentResponseDto responseDto = new PaymentResponseDto();
		responseDto.subCategoryId = payment.getSubCategoryId();
		responseDto.amount = payment.getAmount();
		responseDto.status = payment.getStatus().toString();
		responseDto.createdAt = payment.getCreatedAt();

		return responseDto;
	}
}
