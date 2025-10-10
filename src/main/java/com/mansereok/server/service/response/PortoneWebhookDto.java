package com.mansereok.server.service.response;


import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@ToString
@NoArgsConstructor
public class PortoneWebhookDto {

	@JsonProperty("type")
	private String type;

	@JsonProperty("data")
	private WebhookData data;

	@Getter
	@NoArgsConstructor
	@ToString
	public static class WebhookData {

		@JsonProperty("paymentId")
		private String paymentId; // imp_uid

		@JsonProperty("transactionId")
		private String transactionId;

		@JsonProperty("merchantId")
		private String merchantId;

		@JsonProperty("storeId")
		private String storeId;

		@JsonProperty("orderName")
		private String orderName;

		@JsonProperty("currency")
		private String currency;

		@JsonProperty("amount")
		private Amount amount;

		@JsonProperty("status")
		private String status;

		@JsonProperty("paidAt")
		private String paidAt;

		@JsonProperty("orderDetail")
		private OrderDetail orderDetail;

		@Getter
		@NoArgsConstructor
		@ToString
		public static class Amount {

			@JsonProperty("total")
			private Integer total;

			@JsonProperty("paid")
			private Integer paid;
		}

		@Getter
		@NoArgsConstructor
		@ToString
		public static class OrderDetail {

			@JsonProperty("orderNo")
			private String orderNo; // merchant_uid
		}
	}
}
