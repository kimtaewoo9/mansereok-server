package com.mansereok.server.support;

import com.mansereok.server.domain.payment.client.PortOneClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 결제 영역에서 실제 MySQL 에 붙는 테스트의 바탕 클래스. {@link LocalMySqlTest} 가 목으로 바꾸는 공통 바깥 시스템(Discord,
 * Slack, SES 메일, S3)에 더해 포트원 호출을 목으로 바꾼다.
 *
 * <p>결제 스택의 MySQL 테스트는 모두 이 클래스를 상속한다. 목 조합과 필드 이름이 같아야 스프링 컨텍스트를 테스트 클래스마다 다시
 * 띄우지 않는다. 포트원 응답은 테스트마다 {@link #portOneClient} 에 스텁한다. 목은 테스트가 끝날 때마다 초기화된다.
 */
public abstract class PaymentMySqlTest extends LocalMySqlTest {

	@MockitoBean
	protected PortOneClient portOneClient;
}
