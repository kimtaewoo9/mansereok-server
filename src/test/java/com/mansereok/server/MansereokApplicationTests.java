package com.mansereok.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.support.LocalMySqlTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

class MansereokApplicationTests extends LocalMySqlTest {

	// application-concurrency.yml 의 spring.datasource.url 과 같은 자리표시자·기본값으로 스키마 이름을 읽는다.
	@Value("${CONCURRENCY_DB_NAME:mansereok_test}")
	private String configuredSchemaName;

	@Test
	@DisplayName("concurrency 프로필로 애플리케이션 전체가 뜨고, 설정한 로컬 테스트 스키마(CONCURRENCY_DB_NAME)에 붙는다")
	void contextLoads() {
		// when
		String schemaName = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);

		// then: 로컬 테스트 스키마인지는 LocalMySqlTest 가 테스트마다 LocalDatabaseGuard 로 먼저 확인한다
		assertThat(schemaName).isEqualTo(configuredSchemaName);
	}

}
