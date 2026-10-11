package com.mansereok.server.domain.payment.reconciliation.scheduler;

import com.mansereok.server.domain.payment.reconciliation.service.PaymentReconciliationService;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 새벽 어제 하루치 결제를 대사한다.
 *
 * <p>새벽 5시는 전날 거래의 상태 변경이 대부분 끝난 시각이다. 예외는 여기서 삼킨다. 서비스가 이미 run 을
 * FAILED 로 남기고 실패 알림까지 보냈으므로, 스케줄러 스레드까지 예외를 올릴 이유가 없다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentReconciliationScheduler {

	private final PaymentReconciliationService paymentReconciliationService;
	private final Clock clock;

	@Scheduled(cron = "0 0 5 * * *", zone = "Asia/Seoul")
	public void reconcileYesterday() {
		LocalDate targetDate = LocalDate.now(clock.withZone(PaymentReconciliationService.KST))
			.minusDays(1);

		try {
			paymentReconciliationService.reconcile(targetDate);
		} catch (Exception e) {
			log.error("결제 대사 배치 실패: targetDate={}", targetDate, e);
		}
	}
}
