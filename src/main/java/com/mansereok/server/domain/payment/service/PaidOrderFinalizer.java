package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.springframework.dao.DataIntegrityViolationException;
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
@Slf4j
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
	 * @throws PaymentException 같은 paymentId 의 Payment 가 이미 있어 imp_uid UNIQUE 에 걸린 경우
	 */
	public Payment finalizePaid(Order order, String paymentId, Long amount, LocalDateTime paidAt) {
		// 1. 주문 상태 확정
		order.markPaid(paymentId, paidAt);
		orderRepository.save(order);

		// 2. Payment 생성 및 저장
		Payment savedPayment = savePayment(
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

	/**
	 * Payment 를 저장한다. 호출자의 findByImpUid 선검사와 INSERT 사이에 같은 paymentId 가 먼저 들어가면
	 * (다른 merchantUid 로 동시에 온 요청 등) imp_uid UNIQUE 위반이 나는데, 이는 "이미 처리된 결제" 이므로
	 * 500 이 아니라 선검사와 같은 PaymentException(400) 으로 바꿔 던진다. 예외를 다시 던지므로 트랜잭션은
	 * 그대로 롤백된다.
	 *
	 * <p>Spring 은 Hibernate 의 제약 위반을 종류 구분 없이 DataIntegrityViolationException 으로 번역하므로,
	 * 원인이 UNIQUE 위반({@link ConstraintKind#UNIQUE})일 때만 변환하고 NOT NULL·FK·길이 초과 같은 다른
	 * 무결성 위반은 원인과 무관한 "중복" 메시지가 나가지 않도록 그대로 던진다(500, 웹훅은 재시도).
	 */
	private Payment savePayment(Payment payment) {
		try {
			return paymentRepository.save(payment);
		} catch (DataIntegrityViolationException e) {
			if (!isUniqueConstraintViolation(e)) {
				throw e;
			}
			log.warn("이미 존재하는 결제라 저장하지 못했습니다(UNIQUE 위반): paymentId={}, orderId={}",
				payment.getImpUid(), payment.getOrderId(), e);
			throw new PaymentException("이미 처리된 결제입니다.", e);
		}
	}

	/** 원인 체인에서 Hibernate 의 제약 위반 예외를 찾아 그 종류가 UNIQUE 인지 확인한다. */
	private static boolean isUniqueConstraintViolation(DataIntegrityViolationException e) {
		for (Throwable cause = e.getCause(); cause != null && cause != cause.getCause(); cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation.getKind() == ConstraintKind.UNIQUE;
			}
		}
		return false;
	}
}
