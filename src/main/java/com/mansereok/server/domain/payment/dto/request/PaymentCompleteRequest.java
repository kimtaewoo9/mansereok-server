package com.mansereok.server.domain.payment.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class PaymentCompleteRequest {

	/**
	 * 포트원 결제 ID (프론트가 만들어 포트원에 넘긴 값). 영문·숫자·'_'·'-' 만 받는다. '#'·'?'·'/' 가 섞이면 포트원 조회
	 * 주소에서 뒷부분이 잘려 다른 결제를 조회하게 되므로 입구에서 막는다.
	 */
	@NotBlank(message = "결제 ID는 필수입니다.")
	@Pattern(regexp = PaymentIdFormat.REGEXP, message = PaymentIdFormat.MESSAGE)
	private String paymentId;
	@NotBlank(message = "주문 번호는 필수입니다.")
	private String merchantUid; // 주문 번호 . (백엔드에서 생성)
}
