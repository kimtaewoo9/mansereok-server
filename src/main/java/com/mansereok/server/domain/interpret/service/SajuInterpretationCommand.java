package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.domain.interpret.product.InterpretationProduct;
import com.mansereok.server.domain.interpret.prompt.PromptContext;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 한 사람의 사주 해석(유료 단일, 무료 운세)을 비동기로 시작할 때 넘기는 값 묶음.
 *
 * <p>예전에는 결제 ID 와 상품 번호가 Long 두 개로 나란히 넘어가서, 호출하는 쪽에서 둘을 바꿔 넣어도 컴파일됐다. 이제는 상품을
 * 열거 타입으로 받으므로 결제 ID 와 자리를 바꿔 넣으면 컴파일되지 않는다.
 *
 * @param paymentId 결과를 찾을 결제 ID
 * @param startedAt 컨트롤러가 해석 시작을 표시한 시각. 결과를 쓸 때마다 이 실행이 시작한 해석인지 가리는 데 쓴다.
 * @param product   해석할 상품
 * @param username  요청한 계정. 결과 준비 이메일을 보낼 사용자를 찾는 데만 쓴다.
 * @param person    해석할 사람의 이름, 만세력 계산 결과, 작품명
 */
public record SajuInterpretationCommand(
	long paymentId,
	LocalDateTime startedAt,
	InterpretationProduct product,
	String username,
	PromptContext person
) {

	public SajuInterpretationCommand {
		Objects.requireNonNull(startedAt, "startedAt");
		Objects.requireNonNull(product, "product");
		Objects.requireNonNull(person, "person");
	}
}
