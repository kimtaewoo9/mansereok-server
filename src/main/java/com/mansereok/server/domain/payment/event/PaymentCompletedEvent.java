package com.mansereok.server.domain.payment.event;

/**
 * 주문이 PAID 로 확정되고 Payment 가 저장됐을 때 {@code PaidOrderFinalizer} 가 발행하는 이벤트.
 *
 * <p>커밋 뒤에 도는 리스너가 주문·결제를 다시 읽을 수 있도록 식별자만 싣는다. amount 는 무료 경로(0원)를 리스너가
 * 재조회 없이 걸러내기 위한 값이다. 무료 경로는 전에도 알림이 없었으므로 리스너는 amount 가 0 이면 보내지 않는다.
 *
 * @param orderId     확정된 주문 PK
 * @param paymentPkId 저장된 Payment PK
 * @param amount      결제 금액. 0 이면 무료 결제
 */
public record PaymentCompletedEvent(Long orderId, Long paymentPkId, Long amount) {

}
