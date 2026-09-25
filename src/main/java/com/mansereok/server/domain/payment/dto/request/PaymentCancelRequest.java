package com.mansereok.server.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PaymentCancelRequest {

	/** 환불할 결제 ID. 결제 완료 요청과 같은 형식만 받는다({@link PaymentIdFormat}). */
	@NotBlank(message = "결제 ID는 필수입니다.")
	@Pattern(regexp = PaymentIdFormat.REGEXP, message = PaymentIdFormat.MESSAGE)
	private String paymentId;
	@NotBlank(message = "환불 사유는 필수입니다.")
	private String reason;
}
