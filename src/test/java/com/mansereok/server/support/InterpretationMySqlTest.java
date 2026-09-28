package com.mansereok.server.support;

import com.mansereok.server.domain.interpret.client.OpenAiResponsesClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 해석(interpret) 영역에서 로컬 MySQL 에 붙는 테스트의 바탕 클래스.
 *
 * <p>{@link LocalMySqlTest} 가 바꿔 둔 공통 바깥 시스템(Discord, Slack, SES, S3)에 더해, 해석에서만 쓰는 OpenAI 호출을
 * 목으로 바꾼다. 해석 영역의 MySQL 테스트는 모두 이 클래스를 상속해 목 조합을 맞춘다. 목 조합이 같으면 스프링 컨텍스트를 다시
 * 띄우지 않는다.
 *
 * <p>지켜야 할 규칙(@Transactional 금지, 실행 키로 만든 행만 지우기, JdbcTemplate 로 확인)은 {@link LocalMySqlTest} 와 같다.
 */
public abstract class InterpretationMySqlTest extends LocalMySqlTest {

	@MockitoBean
	protected OpenAiResponsesClient openAiResponsesClient;
}
