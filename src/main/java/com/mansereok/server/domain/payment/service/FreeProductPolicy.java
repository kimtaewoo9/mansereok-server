package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.product.entity.SubCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 상품을 무료로 발급해도 되는지 판정한다.
 *
 * <p>규칙은 "가격이 0원이거나, payment.free-event.sub-category-ids 목록에 든 상품" 이다.
 * 무료 발급 경로(createFreeOrder)는 반드시 이 판정을 거쳐야 유료 상품이 0원 PAID 로 새지 않는다.
 */
@Component
@RequiredArgsConstructor
public class FreeProductPolicy {

	private final FreeEventProperties freeEventProperties;

	public boolean isFree(SubCategory subCategory) {
		Integer price = subCategory.getPrice();
		if (price != null && price == 0) {
			return true;
		}
		return subCategory.getId() != null
			&& freeEventProperties.subCategoryIds().contains(subCategory.getId());
	}
}
