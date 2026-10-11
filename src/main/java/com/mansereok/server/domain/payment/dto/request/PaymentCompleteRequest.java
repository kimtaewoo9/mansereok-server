package com.mansereok.server.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PaymentCompleteRequest {

	/**
	 * 포트원 결제 ID (프론트가 만들어 포트원에 넘긴 값). 형식은 {@link PaymentIdFormat} 을 따른다. null 이면 '결제 ID는
	 * 필수입니다.', 빈 문자열·공백을 포함한 형식 위반이면 '결제 ID 형식이 올바르지 않습니다.' 한 가지로 답한다.
	 */
	@NotNull(message = "결제 ID는 필수입니다.")
	@Pattern(regexp = PaymentIdFormat.REGEXP, message = PaymentIdFormat.MESSAGE)
	private String paymentId;
	@NotBlank(message = "주문 번호는 필수입니다.")
	private String merchantUid; // 주문 번호 . (백엔드에서 생성)
}
