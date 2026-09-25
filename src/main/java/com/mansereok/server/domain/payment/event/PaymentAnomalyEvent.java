package com.mansereok.server.domain.payment.event;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 결제는 확정했지만 사람이 확인해야 하는 일이 생겼을 때 발행하는 이벤트. 커밋 뒤 운영 Discord 채널로 알린다.
 *
 * <p>예를 들어 만료 뒤 늦게 결제된 주문의 쿠폰을 그사이 다른 주문이 이미 쓰고 있거나, 할인 코드 사용 횟수가 최대치를 넘은 경우다.
 * 결제를 되돌리면 돈은 빠져나갔는데 주문이 없는 상태가 되므로, 결제는 그대로 확정하고 이 이벤트로 알리기만 한다.
 * 이미 결제가 끝난 주문에 다른 결제가 또 왔을 때도 발행한다(DuplicatePaymentCanceller). 자동 취소했거나, 그 취소에 실패했거나,
 * 자동 취소하지 않고 사람에게 확인을 맡기는 경우(예전 주문, 부분 취소된 결제, 이미 기록된 결제)다.
 *
 * <p>details 는 알림에 한 줄씩 적을 "항목 이름 → 값" 이다. 넣은 순서대로 적히도록 순서를 지켜 복사해 두고, 밖에서 바꾸지 못하게
 * 읽기 전용으로 감싼다. 고객 개인정보(이름, 이메일)는 넣지 않는다.
 *
 * @param summary 무슨 일이 생겼는지 한 문장
 * @param details 주문 번호, 쿠폰 ID 처럼 확인에 필요한 값
 */
public record PaymentAnomalyEvent(String summary, Map<String, String> details) {

	public PaymentAnomalyEvent {
		Objects.requireNonNull(summary, "summary");
		details = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(details, "details")));
	}
}
