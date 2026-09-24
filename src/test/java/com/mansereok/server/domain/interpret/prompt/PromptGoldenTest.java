package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.service.ManseInterpretationService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 프롬프트 리팩토링의 안전망. 리팩토링 이전 구현이 만든 문자열을 그대로 떠 둔
 * src/test/resources/prompt-golden/*.txt 와 현재 구현의 출력이 같은지 확인한다.
 *
 * <p>시각에 따라 달라지는 부분(오늘 날짜, 오늘 일진, 기준 연도)만 가리고 비교한다.
 * 그 외에는 한 글자라도 다르면 실패한다.
 */
@DisplayName("프롬프트 골든 테스트")
class PromptGoldenTest {

	private static final List<Long> SAJU_IDS = List.of(1L, 2L, 3L, 5L, 9L, 13L, 17L, 18L, 20L, 21L,
		22L, 23L);
	private static final List<Long> COMPATIBILITY_IDS = List.of(4L, 6L, 7L, 8L, 10L, 11L, 14L, 15L,
		19L);
	private static final List<Long> FREE_IDS = List.of(101L, 102L, 103L, 104L, 105L, 106L);

	private static final ManseInterpretationService SERVICE = new ManseInterpretationService(
		null, null, null, null, null, null, null, null, null);

	static Stream<org.junit.jupiter.params.provider.Arguments> cases() {
		Stream.Builder<org.junit.jupiter.params.provider.Arguments> builder = Stream.builder();
		for (Long id : SAJU_IDS) {
			builder.add(org.junit.jupiter.params.provider.Arguments.of("saju", id, ""));
			builder.add(org.junit.jupiter.params.provider.Arguments.of("saju", id, "-edge"));
		}
		for (Long id : COMPATIBILITY_IDS) {
			builder.add(org.junit.jupiter.params.provider.Arguments.of("compatibility", id, ""));
			builder.add(
				org.junit.jupiter.params.provider.Arguments.of("compatibility", id, "-edge"));
		}
		for (Long id : FREE_IDS) {
			builder.add(org.junit.jupiter.params.provider.Arguments.of("free", id, ""));
			builder.add(org.junit.jupiter.params.provider.Arguments.of("free", id, "-edge"));
		}
		return builder.build();
	}

	@ParameterizedTest(name = "{0} {1}{2} 프롬프트는 골든 파일과 같다")
	@MethodSource("cases")
	void promptMatchesGolden(String kind, Long subcategoryId, String variant) throws Exception {
		String actual = switch (kind) {
			case "saju" -> variant.isEmpty()
				? saju(subcategoryId, "김태우", PromptFixtures.person1(), "원피스")
				: saju(subcategoryId, "박하늘", PromptFixtures.personEdge(), "");
			case "compatibility" -> variant.isEmpty()
				? compatibility(subcategoryId, "김태우", PromptFixtures.person1(), "이은정",
				PromptFixtures.person2(), "원피스", "귀멸의 칼날")
				: compatibility(subcategoryId, "박하늘", PromptFixtures.personEdge(), "최서준",
					PromptFixtures.person2(), "", "");
			case "free" -> variant.isEmpty()
				? free(subcategoryId, "김태우", PromptFixtures.person1())
				: free(subcategoryId, "박하늘", PromptFixtures.personEdge());
			default -> throw new IllegalArgumentException("알 수 없는 종류: " + kind);
		};

		String golden = readGolden(subcategoryId + variant + ".txt");

		assertThat(mask(actual))
			.as("%s %s%s 프롬프트", kind, subcategoryId, variant)
			.isEqualTo(mask(golden));
	}

	/**
	 * 시각 의존 부분만 가린다. 오늘 날짜/요일, 오늘 일진, 기준 연도 세 가지뿐이고
	 * 기대값과 실제값에 같은 규칙을 적용하므로 다른 곳의 차이는 그대로 드러난다.
	 */
	private static String mask(String prompt) {
		return prompt
			.replaceAll("\\d{4}년 \\d{2}월 \\d{2}일 [월화수목금토일]요일", "<오늘>")
			.replaceAll("\\d{4}년 \\d{2}월 \\d{2}일", "<오늘>")
			.replaceAll("\\*\\*오늘의 일진\\(Input\\)\\*\\*: .*", "<오늘의 일진>")
			.replaceAll("현재 \\d{4}년", "현재 <올해>년")
			.replaceAll("\\[대운 고정값].*", "<대운 고정값>");
	}

	private static String readGolden(String fileName) {
		try (InputStream in = PromptGoldenTest.class.getClassLoader()
			.getResourceAsStream("prompt-golden/" + fileName)) {
			if (in == null) {
				throw new IllegalStateException("골든 파일이 없습니다: " + fileName);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static String saju(Long subcategoryId, String name,
		ManseryeokCalculationResponse response, String sourceTitle) throws Exception {
		Method method = ManseInterpretationService.class.getDeclaredMethod(
			"createPromptBySubcategory", Long.class, String.class,
			ManseryeokCalculationResponse.class, String.class);
		method.setAccessible(true);
		return (String) method.invoke(SERVICE, subcategoryId, name, response, sourceTitle);
	}

	private static String compatibility(Long subcategoryId, String person1Name,
		ManseryeokCalculationResponse person1Response, String person2Name,
		ManseryeokCalculationResponse person2Response, String person1SourceTitle,
		String person2SourceTitle) throws Exception {
		Method method = ManseInterpretationService.class.getDeclaredMethod(
			"createCompatibilityPromptBySubcategory", Long.class, String.class,
			ManseryeokCalculationResponse.class, String.class,
			ManseryeokCalculationResponse.class, String.class, String.class);
		method.setAccessible(true);
		return (String) method.invoke(SERVICE, subcategoryId, person1Name, person1Response,
			person2Name, person2Response, person1SourceTitle, person2SourceTitle);
	}

	private static String free(Long subcategoryId, String name,
		ManseryeokCalculationResponse response) throws Exception {
		Method method = ManseInterpretationService.class.getDeclaredMethod(
			"createFreePromptBySubcategory", Long.class, String.class,
			ManseryeokCalculationResponse.class);
		method.setAccessible(true);
		return (String) method.invoke(SERVICE, subcategoryId, name, response);
	}
}
