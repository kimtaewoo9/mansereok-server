package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문을 PAID 로 확정하는 공통 절차.
 *
 * <p>completePayment, processWebhook, redeemFreeProduct, createFreeOrder 네 경로가 같은 모양으로
 * 반복하던 "주문 PAID 확정 → Payment 저장 → 연관관계 연결 → 초기 Result 생성"을 한 곳에 모은다.
 *
 * <p>상태 전이 가드는 Order.markPaid 가 담당한다(허용되지 않는 상태면 OrderStateException). 호출자는
 * 멱등성 검사와 금액 검증을 마친 뒤 호출해야 한다.
 *
 * <p>기본 전파(REQUIRED)의 @Transactional 을 둔다. 호출자(PaymentService)의 트랜잭션이 있으면 그대로
 * 참여하고, 없더라도 "주문 PAID + Payment + Result" 가 하나의 트랜잭션으로 묶이도록 스스로 보장한다.
 */
@Component
@RequiredArgsConstructor
@Transactional
public class PaidOrderFinalizer {

	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final ResultService resultService;

	/**
	 * 주문을 PAID 로 확정하고 Payment 와 초기 Result 를 만든다.
	 *
	 * <p>orderRepository.save 는 새 엔티티(persist)든 관리 엔티티(merge)든 같은 인스턴스를 돌려주므로
	 * 넘겨받은 order 를 그대로 이어서 쓴다.
	 *
	 * @param order     PAID 로 확정할 주문. 락으로 조회한 관리 엔티티이거나 아직 저장 전인 새 엔티티 모두 가능하다.
	 * @param paymentId Payment.impUid 로 저장할 결제 식별자. 주문의 paymentId 에도 같은 값이 들어간다.
	 * @param amount    Payment.amount 로 저장할 결제 금액
	 * @param paidAt    주문의 paidAt
	 * @return 저장된 Payment
	 */
	public Payment finalizePaid(Order order, String paymentId, Long amount, LocalDateTime paidAt) {
		// 1. 주문 상태 확정
		order.markPaid(paymentId, paidAt);
		orderRepository.save(order);

		// 2. Payment 생성 및 저장
		Payment savedPayment = paymentRepository.save(
			Payment.create(
				paymentId,
				order.getMerchantUid(),
				amount,
				PaymentStatus.PAID,
				order.getId(),
				order.getUserId(),
				order.getSubCategoryId()
			)
		);

		// 3. 연관관계 연결
		order.linkPayment(savedPayment.getId());
		orderRepository.save(order);

		// 4. 초기 결과지 생성
		resultService.createInitialResult(savedPayment, order);

		return savedPayment;
	}
}
