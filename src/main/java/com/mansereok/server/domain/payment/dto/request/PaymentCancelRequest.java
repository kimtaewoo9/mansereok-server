package com.mansereok.server.domain.payment.dto.request;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PaymentCancelRequest {

	private String paymentId;
	private String reason;
}
