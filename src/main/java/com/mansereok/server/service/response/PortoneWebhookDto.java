package com.mansereok.server.service.response;


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
}
