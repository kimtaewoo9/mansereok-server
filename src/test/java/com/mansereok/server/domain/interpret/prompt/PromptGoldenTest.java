package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 프롬프트 리팩토링의 안전망. 리팩토링 이전 구현이 만든 문자열을 그대로 떠 둔
 * src/test/resources/prompt-golden/*.txt 와 현재 구현의 출력이 같은지 확인한다.
 *
 * <p>시각에 따라 달라지는 값(오늘 날짜, 오늘 일진, 기준 연도)은 골든 파일에 자리표시자로 저장해 두고,
 * 비교 직전에 오늘 값으로 바꿔 넣는다. 실제 프롬프트는 전혀 가공하지 않으므로 자리표시자로 바뀐
 * 값 한 덩어리를 뺀 나머지는 한 글자라도 다르면 실패한다.
 */
@DisplayName("프롬프트 골든 테스트")
class PromptGoldenTest {

	private static final List<Long> SAJU_IDS = List.of(1L, 2L, 3L, 5L, 9L, 13L, 17L, 18L, 20L, 21L,
		22L, 23L);
	private static final List<Long> COMPATIBILITY_IDS = List.of(4L, 6L, 7L, 8L, 10L, 11L, 14L, 15L,
		19L);
	private static final List<Long> FREE_IDS = List.of(101L, 102L, 103L, 104L, 105L, 106L);

	private static final SajuPromptFactory SAJU_PROMPT_FACTORY = new SajuPromptFactory();
	private static final CompatibilityPromptFactory COMPATIBILITY_PROMPT_FACTORY =
		new CompatibilityPromptFactory();

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
	void promptMatchesGolden(String kind, Long subcategoryId, String variant) {
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

		String golden = expandTimeDependentValues(readGolden(subcategoryId + variant + ".txt"));

		assertThat(actual)
			.as("%s %s%s 프롬프트", kind, subcategoryId, variant)
			.isEqualTo(golden);
	}

	/**
	 * 골든 파일의 자리표시자를 오늘 값으로 바꾼다. 프롬프트가 쓰는 시간대·형식을 그대로 따라간다.
	 * (일진과 오늘 날짜는 KST, 나머지는 기본 시간대)
	 */
	private static String expandTimeDependentValues(String golden) {
		LocalDate todayInKst = LocalDate.now(ZoneId.of("Asia/Seoul"));
		LocalDate today = LocalDate.now();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일");
		String dayOfWeek = todayInKst.getDayOfWeek()
			.getDisplayName(TextStyle.FULL, Locale.KOREAN);

		return golden
			.replace("<<TODAY_WITH_DOW>>", todayInKst.format(formatter) + " " + dayOfWeek)
			.replace("<<TODAY_ILJU>>", DaewoonSections.calculateTodayDayPillar(todayInKst))
			.replace("<<TODAY>>", today.format(formatter))
			.replace("<<THIS_YEAR>>", String.valueOf(today.getYear()));
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
		ManseryeokCalculationResponse response, String sourceTitle) {
		return SAJU_PROMPT_FACTORY.create(subcategoryId,
			PromptContext.of(name, response, sourceTitle));
	}

	private static String compatibility(Long subcategoryId, String person1Name,
		ManseryeokCalculationResponse person1Response, String person2Name,
		ManseryeokCalculationResponse person2Response, String person1SourceTitle,
		String person2SourceTitle) {
		return COMPATIBILITY_PROMPT_FACTORY.create(subcategoryId,
			CompatibilityPromptContext.of(person1Name, person1Response, person1SourceTitle,
				person2Name, person2Response, person2SourceTitle));
	}

	private static String free(Long subcategoryId, String name,
		ManseryeokCalculationResponse response) {
		return SAJU_PROMPT_FACTORY.createFree(subcategoryId, PromptContext.of(name, response));
	}
}
