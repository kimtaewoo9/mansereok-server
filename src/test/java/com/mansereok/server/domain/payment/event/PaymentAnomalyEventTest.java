package com.mansereok.server.domain.payment.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentAnomalyEventTest {

	@Test
	@DisplayName("항목은 넣은 순서대로 남고, 이벤트를 만든 뒤 원래 맵을 바꿔도 이벤트의 항목은 바뀌지 않는다")
	void keepsInsertionOrderAndIgnoresLaterChangesToSourceMap() {
		// given
		Map<String, String> source = new LinkedHashMap<>();
		source.put("주문 번호", "order_late_001");
		source.put("주문 ID", "10");
		source.put("쿠폰 ID", "7");

		// when
		PaymentAnomalyEvent event = new PaymentAnomalyEvent("요약", source);
		source.put("쿠폰 ID", "8");
		source.put("추가 항목", "값");

		// then
		assertThat(event.details()).containsExactly(
			Map.entry("주문 번호", "order_late_001"),
			Map.entry("주문 ID", "10"),
			Map.entry("쿠폰 ID", "7"));
	}

	@Test
	@DisplayName("이벤트의 항목은 읽기 전용이다")
	void detailsAreReadOnly() {
		// given
		PaymentAnomalyEvent event = new PaymentAnomalyEvent("요약", Map.of("주문 번호", "order_late_001"));

		// when & then
		assertThatThrownBy(() -> event.details().put("쿠폰 ID", "7"))
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
