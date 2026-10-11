package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MerchantUidGeneratorTest {

	private final MerchantUidGenerator generator = new MerchantUidGenerator();

	@Test
	@DisplayName("forOrder 는 order_ 접두사 + epochMillis + _ + UUID 앞 8자 형식이다")
	void forOrder_matchesFormat() {
		String uid = generator.forOrder();

		assertThat(uid).startsWith("order_");
		assertThat(uid).matches("^order_\\d{13}_[0-9a-f]{8}$");
	}

	@Test
	@DisplayName("forFree 는 free_ 접두사로 forOrder 와 같은 형식이다")
	void forFree_matchesFormat() {
		String uid = generator.forFree();

		assertThat(uid).startsWith("free_");
		assertThat(uid).matches("^free_\\d{13}_[0-9a-f]{8}$");
	}

	@Test
	@DisplayName("freePaymentIdFor 는 무료 주문 번호 앞에 free_ 를 한 번 더 붙여 이미 저장된 무료 결제와 같은 free_free_ 형식을 만든다")
	void freePaymentIdFor_prefixesFreeMerchantUidAgain() {
		// when
		String paymentId = MerchantUidGenerator.freePaymentIdFor("free_1727000000000_ab12cd34");

		// then
		assertThat(paymentId).isEqualTo("free_free_1727000000000_ab12cd34");
	}

	@Test
	@DisplayName("연속 생성해도 값이 겹치지 않는다")
	void generatedValues_areUnique() {
		Set<String> uids = new HashSet<>();
		for (int i = 0; i < 100; i++) {
			uids.add(generator.forOrder());
			uids.add(generator.forFree());
		}

		assertThat(uids).hasSize(200);
	}
}
