package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("출생 일시를 프롬프트 문구로 옮기기")
class SajuProfileSectionsTest {

	@Test
	@DisplayName("생년월일은 한 자리 월·일도 두 자리로 채워 '2001년 06월 12일' 꼴로 쓴다")
	void birthDateTextPadsMonthAndDay() {
		assertThat(SajuProfileSections.birthDateText(LocalDate.of(2001, 6, 12)))
			.isEqualTo("2001년 06월 12일");
	}

	@ParameterizedTest(name = "[{index}] 출생시각 {0} 은 \"{1}\" / \"{2}\"")
	@CsvSource(delimiter = '|', textBlock = """
		# 출생시각 | 문장용       | 데이터 줄용
		09:05      | 09시 05분    | 09:05
		00:00      | 00시 00분    | 00:00
		# 초는 버린다
		23:59:30   | 23시 59분    | 23:59
		# 출생시간을 비워 보낸 사람(null)
		           | 시간 모름    | 시간 모름
		""")
	void birthTimeTexts(LocalTime solarTime, String sentenceText, String shortText) {
		assertThat(SajuProfileSections.birthTimeText(solarTime)).isEqualTo(sentenceText);
		assertThat(SajuProfileSections.birthTimeShortText(solarTime)).isEqualTo(shortText);
	}
}
