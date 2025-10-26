package com.mansereok.server.service.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // 응답의 모든 필드를 매핑하지 않아도 오류가 나지 않도록 설정
public class PortOnePaymentResponse {

	private String id; // 포트원 결제 ID (paymentId)
	private String status;
	private Amount amount;

	@JsonProperty("merchant_uid")
	private String merchantUid; // 가맹점 주문 ID

	private String customData;

	@Data
	@NoArgsConstructor
	public static class Amount {

		private Long total;
	}
}
