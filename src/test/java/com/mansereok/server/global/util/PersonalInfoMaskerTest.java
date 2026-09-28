package com.mansereok.server.global.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PersonalInfoMaskerTest {

	@ParameterizedTest(name = "[{index}] [{0}] → [{1}]")
	@DisplayName("이름은 첫 글자와 마지막 글자만 남기고, 두 글자면 첫 글자만, 한 글자면 전부 가린다")
	@CsvSource(textBlock = """
		# 원래 이름, 가린 이름
		김,          *
		이준,        이*
		홍길동,      홍*동
		남궁민수,    남**수
		John Smith,  J********h
		' 홍길동 ',  홍*동
		# 한자 확장 영역 글자(𠮷)는 char 두 개지만 한 글자로 센다
		𠮷,          *
		𠮷田,        𠮷*
		𠮷野家,      𠮷*家
		""")
	void masksNameByCodePoint(String name, String expected) {
		assertThat(PersonalInfoMasker.maskName(name)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "[{index}] [{0}] → 빈 문자열")
	@DisplayName("공백뿐인 이름은 빈 문자열로 돌려준다")
	@CsvSource(textBlock = """
		''
		'   '
		""")
	void returnsEmptyForBlankName(String name) {
		assertThat(PersonalInfoMasker.maskName(name)).isEmpty();
	}

	@Test
	@DisplayName("이름이 없으면 null 을 그대로 돌려준다")
	void returnsNullForNullName() {
		assertThat(PersonalInfoMasker.maskName(null)).isNull();
	}

	@ParameterizedTest(name = "[{index}] [{0}] → [{1}]")
	@DisplayName("이메일은 '@' 앞의 첫 글자만 남기고 가리며 도메인은 그대로 둔다")
	@CsvSource(textBlock = """
		# 원래 이메일,            가린 이메일
		user@example.com,         u***@example.com
		ab@example.com,           a*@example.com
		a@example.com,            *@example.com
		' user@example.com ',     u***@example.com
		홍길동@example.com,       홍**@example.com
		# char 두 개로 된 글자(𠮷)도 한 글자로 센다
		𠮷a@example.com,          𠮷*@example.com
		# '@' 가 여러 개면 마지막 '@' 뒤를 도메인으로 본다
		'"a@b"@example.com',      "****@example.com
		# '@' 가 없으면 전체를 앞부분으로 보고 가린다
		not-an-email,             n***********
		# 앞부분이 없으면 가릴 것이 없다
		@example.com,             @example.com
		""")
	void masksEmailLocalPart(String email, String expected) {
		assertThat(PersonalInfoMasker.maskEmail(email)).isEqualTo(expected);
	}

	@ParameterizedTest(name = "[{index}] [{0}] → 빈 문자열")
	@DisplayName("공백뿐인 이메일은 빈 문자열로 돌려준다")
	@CsvSource(textBlock = """
		''
		'   '
		""")
	void returnsEmptyForBlankEmail(String email) {
		assertThat(PersonalInfoMasker.maskEmail(email)).isEmpty();
	}

	@Test
	@DisplayName("이메일이 없으면 null 을 그대로 돌려준다")
	void returnsNullForNullEmail() {
		assertThat(PersonalInfoMasker.maskEmail(null)).isNull();
	}
}
