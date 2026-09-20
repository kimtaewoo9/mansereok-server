package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import com.mansereok.server.domain.product.entity.SubCategory;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FreeProductPolicyTest {

	private static final Long EVENT_ID = 19L;

	private FreeProductPolicy policyWithEventIds(List<Long> ids) {
		return new FreeProductPolicy(new FreeEventProperties(ids));
	}

	private SubCategory subCategory(Long id, Integer price) {
		// 생성자가 protected 라 mock 으로 만든다
		SubCategory subCategory = mock(SubCategory.class);
		lenient().when(subCategory.getId()).thenReturn(id);
		lenient().when(subCategory.getPrice()).thenReturn(price);
		return subCategory;
	}

	@Test
	@DisplayName("가격이 0원인 상품은 이벤트 목록과 무관하게 무료다")
	void isFree_priceZero_true() {
		FreeProductPolicy policy = policyWithEventIds(List.of());

		assertThat(policy.isFree(subCategory(101L, 0))).isTrue();
	}

	@Test
	@DisplayName("가격이 있어도 free-event 목록에 든 상품은 무료다")
	void isFree_inEventList_true() {
		FreeProductPolicy policy = policyWithEventIds(List.of(EVENT_ID));

		assertThat(policy.isFree(subCategory(EVENT_ID, 10000))).isTrue();
	}

	@Test
	@DisplayName("가격이 있고 free-event 목록에도 없는 상품은 무료가 아니다")
	void isFree_paidAndNotInList_false() {
		FreeProductPolicy policy = policyWithEventIds(List.of(EVENT_ID));

		assertThat(policy.isFree(subCategory(1L, 10000))).isFalse();
	}

	@Test
	@DisplayName("설정이 없으면 free-event 목록은 빈 목록으로 바인딩되어 가격 0원만 무료다")
	void properties_nullList_becomesEmpty() {
		FreeEventProperties properties = new FreeEventProperties(null);

		assertThat(properties.subCategoryIds()).isEmpty();
		assertThat(new FreeProductPolicy(properties).isFree(subCategory(1L, 10000))).isFalse();
		assertThat(new FreeProductPolicy(properties).isFree(subCategory(1L, 0))).isTrue();
	}
}
