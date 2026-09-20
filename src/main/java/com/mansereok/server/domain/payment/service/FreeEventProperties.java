package com.mansereok.server.domain.payment.service;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * payment.free-event.* 설정 바인딩.
 *
 * <p>가격이 0원이 아니지만 이벤트로 무료 제공하는 상품(SubCategory) id 목록을 담는다.
 * 키가 없으면 빈 목록으로 두어 "가격 0원인 상품만 무료" 로 동작한다. DB 스키마를 바꾸지 않기 위해
 * 설정으로 관리한다.
 */
@ConfigurationProperties(prefix = "payment.free-event")
public record FreeEventProperties(
	List<Long> subCategoryIds
) {

	public FreeEventProperties {
		subCategoryIds = subCategoryIds == null ? List.of() : List.copyOf(subCategoryIds);
	}
}
