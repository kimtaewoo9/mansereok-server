package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.order.entity.Order;
import java.util.Objects;

/**
 * 결제 완료 API 와 웹훅이 주문 행을 잠근 트랜잭션에서 돌려주는 결과. 트랜잭션이 끝난 뒤 무엇을 더 해야 하는지 알려 준다.
 *
 * <p>boolean 플래그 대신 경우마다 타입을 나눈다. 호출자는 default 없는 switch 로 받으므로, 경우가 늘면 받는 쪽이 모두 컴파일
 * 오류로 드러난다.
 */
sealed interface ConfirmOutcome {

	/**
	 * 잠금 안에서 할 일을 모두 마쳤다. 확정했거나, 이미 같은 결제로 확정돼 있었거나, 아직 결제가 끝나지 않았거나, 실패로 기록했다.
	 * 결제 완료 API 는 이 주문을 그대로 돌려준다.
	 */
	record Finished(Order order) implements ConfirmOutcome {

		public Finished {
			Objects.requireNonNull(order, "order");
		}
	}

	/**
	 * 이미 다른 결제로 확정된 주문에 결제가 한 번 더 승인됐다. 잠금과 트랜잭션을 놓은 뒤 {@link DuplicatePaymentCanceller} 가 이
	 * 결제를 포트원에서 취소하고 운영 채널에 알린다.
	 *
	 * @param paidOrder          먼저 확정된 주문. 주문에 적힌 결제 ID 가 먼저 확정된 결제다.
	 * @param duplicatePaymentId 한 번 더 승인된 결제의 ID. 취소할 대상이다.
	 */
	/**
	 * 만료된 주문에 결제가 늦게 들어왔는데, 만료 때 풀어 준 쿠폰을 그사이 다른 주문이 썼거나 할인 코드가 선착순 횟수에 닿아 할인을 다시
	 * 잡지 못했다. 주문은 EXPIRED 로 두고, 잠금과 트랜잭션을 놓은 뒤 {@link DuplicatePaymentCanceller} 가 이 결제를 취소한다.
	 * 확정하면 쿠폰 한 장·선착순 한 자리의 할인이 두 결제에 들어간다.
	 */
	record LatePaymentWithoutDiscount(Order expiredOrder, String paymentId) implements ConfirmOutcome {

		public LatePaymentWithoutDiscount {
			Objects.requireNonNull(expiredOrder, "expiredOrder");
			Objects.requireNonNull(paymentId, "paymentId");
		}
	}

	record DuplicatePayment(Order paidOrder, String duplicatePaymentId) implements ConfirmOutcome {

		public DuplicatePayment {
			Objects.requireNonNull(paidOrder, "paidOrder");
			Objects.requireNonNull(duplicatePaymentId, "duplicatePaymentId");
		}
	}
}
