package com.mansereok.server.domain.interpret.postprocess;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.interpret.postprocess.NormalizeFixtures.Sample;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
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

	/**
	 * 표본과 골든 파일이 정확히 1:2 로 맞물리는지 본다. 표본만 늘리고 골든을 안 뜬 경우와,
	 * 표본을 지웠는데 골든만 남은 경우를 모두 잡는다.
	 */
	@DisplayName("골든 파일과 표본은 빠짐없이 짝이 맞는다")
	@Test
	void goldenFilesMatchSamples() throws Exception {
		URL directory = Objects.requireNonNull(
			NormalizeGoldenTest.class.getClassLoader().getResource("normalize-golden"),
			"normalize-golden 디렉터리를 찾을 수 없습니다");

		List<String> actualFiles;
		try (Stream<Path> files = Files.list(Path.of(directory.toURI()))) {
			actualFiles = files.map(path -> path.getFileName().toString()).sorted().toList();
		}

		List<String> expectedFiles = NormalizeFixtures.samples().keySet().stream()
			.flatMap(name -> Stream.of(name + ".analysis.txt", name + ".summary.txt"))
			.sorted()
			.toList();

		assertThat(actualFiles).containsExactlyElementsOf(expectedFiles);
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
