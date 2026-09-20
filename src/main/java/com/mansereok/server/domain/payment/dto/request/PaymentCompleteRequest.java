package com.mansereok.server.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PaymentCompleteRequest {

	@NotBlank(message = "결제 ID는 필수입니다.")
	private String paymentId; // 포트원 결제 ID (포트원에서 생성)
	@NotBlank(message = "주문 번호는 필수입니다.")
	private String merchantUid; // 주문 번호 . (백엔드에서 생성)
}
