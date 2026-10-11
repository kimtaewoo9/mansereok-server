package com.mansereok.server.domain.payment.dto.response;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * 포트원 웹훅 본문 중 우리가 쓰는 필드만 매핑한다. 본문의 tx_id 처럼 쓰지 않는 필드는 매핑하지 않고, ObjectMapper 설정과
 * 상관없이 무시한다.
 */
@Getter
@NoArgsConstructor
@ToString
@JsonIgnoreProperties(ignoreUnknown = true)
public class PortoneWebhookDto {

	@JsonProperty("payment_id")
	private String paymentId;  // 결제 ID

	private String status;  // 결제 상태: Ready, Paid, Failed, Cancelled 등

	// 신형 웹훅 본문(2024-04-25 버전)의 이벤트 종류. 예: "Transaction.Paid". 이 형식에는 status 가 없다.
	// 서비스는 이 형식을 처리하지 않고, status 없이 type 만 오면 warn 으로 남긴다.
	private String type;
}
