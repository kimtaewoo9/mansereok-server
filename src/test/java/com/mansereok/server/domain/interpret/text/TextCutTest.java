package com.mansereok.server.domain.interpret.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@DisplayName("TextCut - 글자 수 상한에 맞춰 코드 포인트 경계에서 자르기")
class TextCutTest {

	@ParameterizedTest(name = "[{index}] \"{0}\" 을 {1} 칸에 맞추면 \"{2}\"")
	@CsvSource(delimiter = '|', textBlock = """
		# 원문    | 상한 | 결과
		가나다    | 4    | 가나다
		가나다    | 3    | 가나다
		가나다    | 2    | 가나
		가나다    | 0    | ''
		''        | 0    | ''
		# 이모지(😀)는 코드 유닛 두 칸이다. 상한이 그 사이에 걸리면 이모지를 통째로 뺀다
		가😀      | 3    | 가😀
		가😀      | 2    | 가
		😀😀      | 3    | 😀
		😀        | 1    | ''
		# 앞뒤 공백은 그대로 둔다. 공백 정리는 부르는 쪽이 한다
		'가나 다' | 3    | '가나 '
		""")
	void cutsAtCodePointBoundary(String text, int maxLength, String expected) {
		// when
		String cut = TextCut.atCodePointBoundary(text, maxLength);

		// then
		assertThat(cut).isEqualTo(expected);
	}

	@Test
	@DisplayName("상한이 음수이면 예외가 난다")
	void rejectsNegativeMaxLength() {
		assertThatThrownBy(() -> TextCut.atCodePointBoundary("가나다", -1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("maxLength 는 0 이상이어야 합니다: -1");
	}

	@Test
	@DisplayName("원문이 null 이면 예외가 난다")
	void rejectsNullText() {
		assertThatThrownBy(() -> TextCut.atCodePointBoundary(null, 3))
			.isInstanceOf(NullPointerException.class)
			.hasMessage("text");
	}
}
