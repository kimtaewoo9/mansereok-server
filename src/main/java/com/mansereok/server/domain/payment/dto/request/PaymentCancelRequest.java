package com.mansereok.server.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PaymentCancelRequest {

	@NotBlank(message = "결제 ID는 필수입니다.")
	private String paymentId;
	@NotBlank(message = "환불 사유는 필수입니다.")
	private String reason;
}
