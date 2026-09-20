package com.mansereok.server.domain.payment.event;

import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 결제 완료 Discord 알림. 결제 확정 트랜잭션이 커밋된 뒤 별도 스레드에서 보낸다.
 *
 * <p>예전에는 completePayment·processWebhook 이 주문 행 락과 DB 커넥션을 쥔 채 Discord 웹훅을 동기로 불렀다(M6).
 * {@code AFTER_COMMIT} 이라 롤백된 결제에는 알림이 가지 않고, {@code @Async} 라 알림 지연·실패가 응답 시간에
 * 영향을 주지 않는다. 실패는 error 로그로 삼킨다.
 *
 * <p>실행 스레드는 AsyncConfig 의 기본 풀 {@code threadPoolTaskExecutor} 다. 이벤트에는 식별자만 실려 오므로
 * 주문·결제·사용자·상품을 여기서 다시 읽는다(각 조회는 리포지토리의 읽기 전용 트랜잭션).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentCompletedNotificationListener {

	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;
	private final UserRepository userRepository;
	private final SubCategoryRepository subCategoryRepository;
	private final DiscordNotificationService discordNotificationService;

	@Async("threadPoolTaskExecutor")
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void on(PaymentCompletedEvent event) {
		// 무료 경로(0원)는 전에도 알림이 없었다
		if (event.amount() == null || event.amount() == 0L) {
			log.debug("무료 결제라 Discord 알림을 보내지 않습니다: orderId={}, paymentPkId={}",
				event.orderId(), event.paymentPkId());
			return;
		}

		try {
			Order order = orderRepository.findById(event.orderId()).orElse(null);
			Payment payment = paymentRepository.findById(event.paymentPkId()).orElse(null);
			if (order == null || payment == null) {
				log.warn("Discord 결제 알림 전송 실패: 주문(ID:{}) 또는 결제(ID:{}) 정보를 찾을 수 없습니다.",
					event.orderId(), event.paymentPkId());
				return;
			}

			User user = order.getUserId() == null ? null
				: userRepository.findById(order.getUserId()).orElse(null);
			SubCategory subCategory = subCategoryRepository.findById(order.getSubCategoryId())
				.orElse(null);
			if (user == null || subCategory == null) {
				log.warn("Discord 결제 알림 전송 실패: 사용자(ID:{}) 또는 상품(ID:{}) 정보를 찾을 수 없습니다.",
					order.getUserId(), order.getSubCategoryId());
				return;
			}

			discordNotificationService.sendPaymentCompletedNotification(
				user.getName(),
				user.getEmail(),
				payment.getAmount(),
				subCategory.getTitle(),
				order.getPaidAt(),
				order.getAppliedDiscountCode(),
				order.getOriginalAmount()
			);
		} catch (Exception e) {
			// 알림 실패가 결제 처리에 영향을 주지 않도록 삼킨다 (이미 커밋된 뒤라 롤백도 없다)
			log.error("Discord 결제 알림 전송 중 오류 (무시됨): orderId={}, paymentPkId={}",
				event.orderId(), event.paymentPkId(), e);
		}
	}
}
