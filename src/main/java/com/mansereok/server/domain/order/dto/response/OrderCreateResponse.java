package com.mansereok.server.domain.order.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateResponse {

	private Long orderId;
	private String merchantUid;
	private Integer amount;
	private String productName;
}
