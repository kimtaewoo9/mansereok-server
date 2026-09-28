package com.mansereok.server.support;

import com.mansereok.server.domain.interpret.client.OpenAiResponsesClient;
import com.mansereok.server.domain.interpret.service.SajuResultService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

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

	/**
	 * 진짜 SajuResultService 를 감싼 스파이. 스텁하지 않은 메서드는 진짜를 그대로 부르므로, 스텁하지 않는 테스트에서는 진짜 빈과
	 * 같다. 해석 흐름 테스트가 입력 정보 채우기 같은 DB 단계를 일부러 실패시킬 때 스텁한다. 스텁은 테스트가 끝날 때마다 지워진다.
	 *
	 * <p>스파이를 쓰는 테스트 클래스에만 두면 그 클래스만 목 조합이 달라 스프링 컨텍스트를 하나 더 띄운다. 그래서 해석 MySQL 테스트가
	 * 모두 같은 조합을 쓰도록 여기에 둔다.
	 */
	@MockitoSpyBean
	protected SajuResultService sajuResultService;
}
