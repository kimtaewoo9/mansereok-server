package com.mansereok.server.domain.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 포트원 V2 결제 조회 응답 중 우리가 쓰는 필드만 매핑한다.
 *
 * <p>V2 결제 객체에는 주문 번호(V1 의 merchant_uid) 필드가 없다. 주문 번호는 프론트가 실어 보낸 customData 에서
 * {@link WebhookCustomData} 로 꺼낸다.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // 응답의 모든 필드를 매핑하지 않아도 오류가 나지 않도록 설정
public class PortOnePaymentResponse {

	private String id; // 포트원 결제 ID (paymentId)
	private String status;
	private Amount amount;

	private String customData;

	@Data
	@NoArgsConstructor
	public static class Amount {

		private Long total;
	}
}
