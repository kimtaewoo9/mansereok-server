package com.mansereok.server.domain.payment.dto.response;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@NoArgsConstructor
@ToString
public class PortoneWebhookDto {

	@JsonProperty("tx_id")
	private String txId;  // 트랜잭션 ID

	@JsonProperty("payment_id")
	private String paymentId;  // 결제 ID

	private String status;  // 결제 상태: Ready, Paid, Failed, Cancelled 등

	@JsonProperty("merchant_uid")
	private String merchantUid;

	// 신형 웹훅 본문(2024-04-25 버전)의 이벤트 종류. 예: "Transaction.Paid". 이 형식에는 status 가 없다.
	// 서비스는 이 형식을 처리하지 않고, status 없이 type 만 오면 warn 으로 남긴다.
	private String type;
}
