package com.mansereok.server.domain.payment.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 포트원 V2 결제 다건 조회(GET /payments) 응답 한 페이지.
 *
 * <p>응답에는 페이지 정보가 함께 오므로, 호출자는 {@code page.totalCount} 를 보고 다음 페이지를 더 부를지
 * 판단한다. 대사에 필요한 필드만 두고 나머지는 무시한다.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PortOnePaymentPage {

	private List<PortOnePaymentResponse> items;
	private Page page;

	@Data
	@NoArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Page {

		private int number;
		private int size;
		private int totalCount;
	}
}
