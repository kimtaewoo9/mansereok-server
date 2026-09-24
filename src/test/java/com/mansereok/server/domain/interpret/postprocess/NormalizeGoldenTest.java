package com.mansereok.server.domain.interpret.postprocess;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.interpret.postprocess.NormalizeFixtures.Sample;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 후처리 리팩토링의 안전망. 규칙을 서비스에서 떼어내기 전 구현이 만든 결과를 그대로 떠 둔
 * src/test/resources/normalize-golden/*.txt 와 현재 구현의 출력이 같은지 확인한다.
 *
 * <p>후처리에는 시각에 따라 달라지는 값이 없으므로 골든 파일을 전혀 가공하지 않고 통째로 비교한다.
 * 한 글자라도 달라지면 실패한다.
 */
@DisplayName("후처리 골든 테스트")
class NormalizeGoldenTest {

	private final AnalysisNormalizer normalizer = new AnalysisNormalizer();

	static Stream<Arguments> cases() {
		return NormalizeFixtures.samples().entrySet().stream()
			.map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
	}

	@ParameterizedTest(name = "{0} 본문 후처리 결과는 골든 파일과 같다")
	@MethodSource("cases")
	void analysisMatchesGolden(String name, Sample sample) {
		assertThat(normalizer.normalizeAnalysis(sample.subcategoryId(), sample.text()))
			.as("%s 본문", name)
			.isEqualTo(readGolden(name + ".analysis.txt"));
	}

	@ParameterizedTest(name = "{0} 요약 후처리 결과는 골든 파일과 같다")
	@MethodSource("cases")
	void summaryMatchesGolden(String name, Sample sample) {
		assertThat(normalizer.normalizeSummary(sample.subcategoryId(), sample.text()))
			.as("%s 요약", name)
			.isEqualTo(readGolden(name + ".summary.txt"));
	}

	@DisplayName("골든 파일과 표본은 짝이 맞는다")
	@Test
	void everySampleHasGolden() {
		for (Map.Entry<String, Sample> entry : NormalizeFixtures.samples().entrySet()) {
			assertThat(readGolden(entry.getKey() + ".analysis.txt")).isNotNull();
			assertThat(readGolden(entry.getKey() + ".summary.txt")).isNotNull();
		}
	}

	private static String readGolden(String fileName) {
		try (InputStream in = NormalizeGoldenTest.class.getClassLoader()
			.getResourceAsStream("normalize-golden/" + fileName)) {
			if (in == null) {
				throw new IllegalStateException("골든 파일이 없습니다: " + fileName);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
