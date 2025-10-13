package com.mansereok.server.service.response;

import com.mansereok.server.entity.Payment;
import java.time.LocalDateTime;
import lombok.Getter;

@Getter
public class PaymentResponseDto {

	private String serviceName; // 서비스 이름 (예: "사주 상담")
	private Long amount;        // 결제 금액
	private String status;      // 결제 상태 (예: "완료", "진행중")
	private LocalDateTime createdAt; // 결제 날짜

	// Payment 엔티티를 이 DTO로 변환하는 생성자
	public static PaymentResponseDto create(Payment payment) {
		PaymentResponseDto responseDto = new PaymentResponseDto();
		// 원래 Order 엔티티를 조회해서 Product(상품)의 이름을 가져와야함 .
		responseDto.serviceName = payment.getMerchantUid();
		responseDto.amount = payment.getAmount();
		responseDto.status = payment.getStatus().toString();
		responseDto.createdAt = payment.getCreatedAt();

		return responseDto;
	}
}
