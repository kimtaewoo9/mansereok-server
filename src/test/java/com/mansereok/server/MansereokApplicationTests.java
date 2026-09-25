package com.mansereok.server;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.support.LocalMySqlTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MansereokApplicationTests extends LocalMySqlTest {

	@Test
	@DisplayName("concurrency 프로필로 애플리케이션 전체가 뜨고, 이름에 _test 가 붙은 로컬 테스트 스키마에 붙는다")
	void contextLoads() {
		// when
		String schemaName = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);

		// then: mansereok_test 처럼 _test 로 끝나거나, 스택마다 따로 둔 mansereok_test_develop 처럼 중간에 _test_ 가 있다
		assertThat(schemaName).containsPattern("_test(_|$)");
	}

}
