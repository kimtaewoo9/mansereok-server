package com.mansereok.server.domain.payment.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.payment.client.PortOneProperties;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 대사가 환불 도중에 본 불일치를 다시 읽기 전에 기다리는 시간을 검증한다.
 *
 * <p>이 시간이 환불이 CANCEL_REQUESTED 에 머물 수 있는 시간보다 짧으면, 진행 중인 환불을 멈춘 환불로 알린다. 환불은 포트원 취소
 * 호출(연결 한도 + 응답 한도 안에서 끝나거나 실패한다)을 마친 뒤 CANCELLED 를 커밋하므로, 기다리는 시간은 포트원 설정을 따라가야
 * 한다.
 */
class PaymentReconciliationRefundSettleWaitTest {

	@ParameterizedTest(name = "[{index}] 연결 {0}ms + 응답 {1}ms → {2}초")
	@CsvSource(textBlock = """
		# 운영 기본값
		3000,  10000, 23
		5000,  30000, 45
		""")
	@DisplayName("재확인 전에 기다리는 시간은 포트원 연결 한도와 응답 한도에 CANCELLED 커밋 여유 10초를 더한 값이다")
	void coversPortOneCancelCallAndCommit(int connectTimeoutMs, int readTimeoutMs, long expectedSeconds) {
		// given
		PortOneProperties properties = new PortOneProperties("test-secret", null, connectTimeoutMs,
			readTimeoutMs, new PortOneProperties.Webhook("test-webhook-secret"));

		// when
		Duration wait = PaymentReconciliationService.refundSettleWaitOf(properties);

		// then
		assertThat(wait).isEqualTo(Duration.ofSeconds(expectedSeconds));
	}
}
