package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.domain.interpret.product.InterpretationProduct;
import com.mansereok.server.domain.interpret.prompt.CompatibilityPromptContext;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 두 사람의 궁합 해석(유료 궁합, 무료 궁합)을 비동기로 시작할 때 넘기는 값 묶음.
 *
 * <p>{@link SajuInterpretationCommand} 와 같은 이유로 묶는다. 예전에는 유료 궁합이 열 개, 무료 궁합이 여덟 개의 인자를 받았다(두
 * 사람의 이름·만세력, 상품 번호, 결제 ID, 시작 시각, 계정, 유료는 두 사람의 작품명까지). 그 가운데 상품 번호와 결제 ID 가 Long 두
 * 개로 나란히 있었다.
 *
 * @param paymentId 결과를 찾을 결제 ID
 * @param startedAt 컨트롤러가 해석 시작을 표시한 시각. 결과를 쓸 때마다 이 실행이 시작한 해석인지 가리는 데 쓴다.
 * @param product   해석할 궁합 상품
 * @param username  요청한 계정. 결과 준비 이메일을 보낼 사용자를 찾는 데만 쓴다.
 * @param persons   두 사람의 이름, 만세력 계산 결과, 작품명
 */
public record CompatibilityInterpretationCommand(
	long paymentId,
	LocalDateTime startedAt,
	InterpretationProduct product,
	String username,
	CompatibilityPromptContext persons
) {

	public CompatibilityInterpretationCommand {
		Objects.requireNonNull(startedAt, "startedAt");
		Objects.requireNonNull(product, "product");
		Objects.requireNonNull(persons, "persons");
	}
}
