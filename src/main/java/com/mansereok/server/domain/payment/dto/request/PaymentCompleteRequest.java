package com.mansereok.server.domain.payment.dto.request;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PaymentCompleteRequest {

	private String paymentId; // 포트원 결제 ID (포트원에서 생성)
	private String merchantUid; // 주문 번호 . (백엔드에서 생성)
}
