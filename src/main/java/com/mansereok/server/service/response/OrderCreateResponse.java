package com.mansereok.server.service.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreateResponse {

	private Long orderId; // orderId를 프론트에게 전달 ..
	private String merchantUid;
	private Integer amount; // 가격 알려주고
	private String productName;
}
