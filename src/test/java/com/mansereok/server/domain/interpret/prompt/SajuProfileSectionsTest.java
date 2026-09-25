package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("출생 일시를 프롬프트 문구로 옮기기")
class SajuProfileSectionsTest {

	private static final LocalDate BIRTH_DATE = LocalDate.of(2001, 6, 12);

	@ParameterizedTest(name = "[{index}] 출생시각 {0} → \"{1}\"")
	@CsvSource(delimiter = '|', textBlock = """
		# 출생시각 | 시작 문장에 넣는 출생 일시
		09:05      | 2001년 06월 12일 09시 05분
		00:00      | 2001년 06월 12일 00시 00분
		# 초는 버린다
		23:59:30   | 2001년 06월 12일 23시 59분
		# 출생시간을 비워 보낸 사람(null)
		           | 2001년 06월 12일
		""")
	@DisplayName("시작 문장에는 월·일을 두 자리로 채운 출생 일시를 쓰고, 출생시간이 없으면 날짜만 쓴다")
	void birthDateTimePhraseLeavesOutUnknownTime(LocalTime solarTime, String expected) {
		// when
		String phrase = SajuProfileSections.birthDateTimePhrase(BIRTH_DATE, solarTime);

		// then
		assertThat(phrase).isEqualTo(expected);
	}

	@ParameterizedTest(name = "[{index}] 출생시각 {0} → \"{1}\"")
	@CsvSource(delimiter = '|', textBlock = """
		# 출생시각 | 숫자 꼴 시작 문장에 넣는 출생 일시
		09:05      | 2001-06-12 09:05
		# 초는 버린다
		23:59:30   | 2001-06-12 23:59
		# 출생시간을 비워 보낸 사람(null)
		           | 2001-06-12
		""")
	@DisplayName("숫자 꼴 시작 문장에는 '2001-06-12 09:05' 로 쓰고, 출생시간이 없으면 날짜만 쓴다")
	void birthDateTimeDigitPhraseLeavesOutUnknownTime(LocalTime solarTime, String expected) {
		// when
		String phrase = SajuProfileSections.birthDateTimeDigitPhrase(BIRTH_DATE, solarTime);

		// then
		assertThat(phrase).isEqualTo(expected);
	}

	@ParameterizedTest(name = "[{index}] 출생시각 {0} → \"{1}\"")
	@CsvSource(delimiter = '|', textBlock = """
		# 출생시각 | 데이터 줄에 쓰는 출생시각
		09:05      | 09:05
		00:00      | 00:00
		# 초는 버린다
		23:59:30   | 23:59
		# 출생시간을 비워 보낸 사람(null)
		           | 시간 모름
		""")
	@DisplayName("데이터 줄에는 출생시각을 '09:05' 로 쓰고, 출생시간이 없으면 시간 모름이라고 쓴다")
	void birthTimeShortTextSaysTimeUnknown(LocalTime solarTime, String expected) {
		// when
		String text = SajuProfileSections.birthTimeShortText(solarTime);

		// then
		assertThat(text).isEqualTo(expected);
	}
}
