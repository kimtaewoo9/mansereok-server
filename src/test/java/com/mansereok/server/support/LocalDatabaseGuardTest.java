package com.mansereok.server.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LocalDatabaseGuardTest {

	@Nested
	@DisplayName("연결 URL 과 스키마 이름을 함께 검사할 때")
	class Check {

		@ParameterizedTest(name = "[{index}] {0} / {1}")
		@DisplayName("호스트가 127.0.0.1·localhost 이고 스키마 이름이 _test 로 끝나거나 중간에 _test_ 가 있으면 통과시킨다")
		@CsvSource(textBlock = """
			# 연결 URL, 스키마 이름
			jdbc:mysql://127.0.0.1:3307/mansereok_test?zeroDateTimeBehavior=CONVERT_TO_NULL, mansereok_test
			jdbc:mysql://localhost:3307/mansereok_test,                                    mansereok_test
			# 호스트 이름은 대소문자를 가리지 않는다
			jdbc:mysql://LOCALHOST:3307/mansereok_test,                                    mansereok_test
			# 스택마다 따로 둔 테스트 스키마
			jdbc:mysql://127.0.0.1:3307/mansereok_test_develop,                            mansereok_test_develop
			""")
		void acceptsLocalTestSchema(String jdbcUrl, String schemaName) {
			assertThatCode(() -> LocalDatabaseGuard.check(jdbcUrl, schemaName))
				.doesNotThrowAnyException();
		}

		@ParameterizedTest(name = "[{index}] {0} / {1}")
		@DisplayName("호스트가 이 PC 가 아니거나 스키마 이름에 _test 가 없으면 테스트를 멈춘다")
		@CsvSource(nullValues = "NULL", textBlock = """
			# 연결 URL, 스키마 이름
			jdbc:mysql://10.0.0.5:3306/mansereok_test,                                            mansereok_test
			jdbc:mysql://mansereok.abc123.ap-northeast-2.rds.amazonaws.com:3306/mansereok_test, mansereok_test
			jdbc:mysql://127.0.0.1.example.com:3306/mansereok_test,                              mansereok_test
			jdbc:mysql://127.0.0.1:3307/mansereok,                                                mansereok
			jdbc:mysql://127.0.0.1:3307/mansereok_test,                                           mansereok
			jdbc:mysql://127.0.0.1:3307/mysql,                                                    mysql
			jdbc:mysql://127.0.0.1:3307/mansereok_latest,                                         mansereok_latest
			jdbc:mysql://127.0.0.1:3307/mansereok_testing,                                        mansereok_testing
			jdbc:mysql://127.0.0.1:3307/mansereok_test,                                           NULL
			NULL,                                                                                 mansereok_test
			'jdbc:mysql://127.0.0.1,10.0.0.5:3306/mansereok_test',                                mansereok_test
			""")
		void rejectsOtherDatabases(String jdbcUrl, String schemaName) {
			assertThatThrownBy(() -> LocalDatabaseGuard.check(jdbcUrl, schemaName))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageStartingWith("로컬 테스트 DB 가 아니다");
		}
	}

	@Nested
	@DisplayName("DB 에 붙기 전에 설정된 URL 만으로 검사할 때")
	class CheckUrl {

		@Test
		@DisplayName("URL 경로의 스키마 이름에 _test 가 있고 호스트가 이 PC 면 통과시킨다")
		void acceptsLocalTestUrl() {
			assertThatCode(() -> LocalDatabaseGuard.checkUrl(
				"jdbc:mysql://127.0.0.1:3307/mansereok_test?zeroDateTimeBehavior=CONVERT_TO_NULL"))
				.doesNotThrowAnyException();
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@DisplayName("URL 경로에 _test 스키마가 없거나 호스트가 이 PC 가 아니면 테스트를 멈춘다")
		@ValueSource(strings = {
			"jdbc:mysql://127.0.0.1:3307/mansereok?zeroDateTimeBehavior=CONVERT_TO_NULL",
			"jdbc:mysql://127.0.0.1:3307/",
			"jdbc:mysql://127.0.0.1:3307",
			"jdbc:mysql://10.0.0.5:3306/mansereok_test"
		})
		void rejectsOtherUrls(String jdbcUrl) {
			assertThatThrownBy(() -> LocalDatabaseGuard.checkUrl(jdbcUrl))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageStartingWith("로컬 테스트 DB 가 아니다");
		}
	}
}
