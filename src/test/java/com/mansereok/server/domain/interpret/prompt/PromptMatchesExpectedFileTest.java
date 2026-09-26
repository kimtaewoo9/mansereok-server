package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 프롬프트 리팩토링의 안전망. 리팩토링 이전 구현이 만든 문자열을 그대로 저장해 둔
 * src/test/resources/expected-prompts/*.txt 와 현재 구현의 출력이 같은지 확인한다.
 *
 * <p>팩토리에 고정 시계({@link PromptFixtures#FIXED_CLOCK}, 2026-09-25 10:00 서울)를 넣는다. 그래서 오늘 날짜,
 * 오늘 일진, 현재 연도도 기대 결과 파일에 값 그대로 들어 있고, 실제 프롬프트와 기대 결과 파일 어느 쪽도 가공하지 않는다.
 * 한 글자라도 다르면 실패하고, 테스트를 돌리는 날짜와 상관없이 결과가 같다.
 *
 * <p>변이(mutation)로 확인한 덮는 범위:
 * <ul>
 *   <li>대운 역행 분기 - -reverse 기대 결과 파일이 덮는다. 음수 나머지 보정 {@code (x % 60 + 60) % 60} 의
 *       60 을 61 로 바꾸면 세 파일이 모두 깨진다.</li>
 *   <li>궁합 두 번째 사람의 결측 분기 - -edge2 기대 결과 파일이 덮는다.</li>
 *   <li>오늘 일진 - 105 기대 결과 파일에 2026-09-25 의 일진이 값 그대로 들어 있어 일진 계산이 틀리면 깨진다.
 *       여러 날짜를 손으로 계산한 기대값은 {@link DaewoonSectionsTest} 가 들고 있다.</li>
 * </ul>
 */
@DisplayName("프롬프트가 기대 결과 파일과 같은지")
class PromptMatchesExpectedFileTest {

	private static final List<Long> SAJU_IDS = List.of(1L, 2L, 3L, 5L, 9L, 13L, 17L, 18L, 20L, 21L,
		22L, 23L);
	private static final List<Long> COMPATIBILITY_IDS = List.of(4L, 6L, 7L, 8L, 10L, 11L, 14L, 15L,
		19L);
	private static final List<Long> FREE_IDS = List.of(101L, 102L, 103L, 104L, 105L, 106L);

	private static final SajuPromptFactory SAJU_PROMPT_FACTORY =
		new SajuPromptFactory(PromptFixtures.FIXED_CLOCK);
	private static final CompatibilityPromptFactory COMPATIBILITY_PROMPT_FACTORY =
		new CompatibilityPromptFactory(PromptFixtures.FIXED_CLOCK);

	/**
	 * 대운 역행 기대 결과 파일을 만든 상품. 기준연도가 2026 으로 코드에 박혀 있어 해가 바뀌어도 대운 칸이
	 * 움직이지 않는 상품만 골랐다(18=신년운세, 101=2026 변화, 106=3월 월운).
	 * 역행 계산 자체는 상품과 무관한 한 곳(DaewoonSections)에서 하므로 27개 전부를 만들 필요가 없다.
	 */
	private static final List<Long> REVERSE_SAJU_IDS = List.of(18L);
	private static final List<Long> REVERSE_FREE_IDS = List.of(101L, 106L);

	/**
	 * 출생시간을 모르는 사람(solarTime null)의 기대 결과 파일을 만든 상품. 출생시각을 쓰는 곳은 모든 상품이
	 * 같은 도우미(SajuProfileSections)를 거치므로, 그 도우미를 쓰면서 예전에 NullPointerException 이 나던
	 * 인생총운(1) 하나로 "시간 모름" 문구의 모양을 못박는다.
	 */
	private static final List<Long> TIME_UNKNOWN_SAJU_IDS = List.of(1L);

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
			builder.add(
				org.junit.jupiter.params.provider.Arguments.of("compatibility", id, "-edge2"));
		}
		for (Long id : FREE_IDS) {
			builder.add(org.junit.jupiter.params.provider.Arguments.of("free", id, ""));
			builder.add(org.junit.jupiter.params.provider.Arguments.of("free", id, "-edge"));
		}
		for (Long id : REVERSE_SAJU_IDS) {
			builder.add(org.junit.jupiter.params.provider.Arguments.of("saju", id, "-reverse"));
		}
		for (Long id : REVERSE_FREE_IDS) {
			builder.add(org.junit.jupiter.params.provider.Arguments.of("free", id, "-reverse"));
		}
		for (Long id : TIME_UNKNOWN_SAJU_IDS) {
			builder.add(org.junit.jupiter.params.provider.Arguments.of("saju", id, "-time-unknown"));
		}
		return builder.build();
	}

	@ParameterizedTest(name = "{0} {1}{2} 프롬프트는 기대 결과 파일과 같다")
	@MethodSource("cases")
	void promptMatchesExpectedFile(String kind, Long subcategoryId, String variant) {
		String actual = switch (kind) {
			case "saju" -> switch (variant) {
				case "" -> saju(subcategoryId, "김태우", PromptFixtures.person1(), "원피스");
				case "-edge" -> saju(subcategoryId, "박하늘", PromptFixtures.personEdge(), "");
				case "-reverse" ->
					saju(subcategoryId, "강민호", PromptFixtures.personReverseDaewoon(), "원피스");
				case "-time-unknown" ->
					saju(subcategoryId, "김태우", PromptFixtures.personTimeUnknown(), "원피스");
				default -> throw new IllegalArgumentException("알 수 없는 변이: " + variant);
			};
			case "compatibility" -> switch (variant) {
				case "" -> compatibility(subcategoryId, "김태우", PromptFixtures.person1(), "이은정",
					PromptFixtures.person2(), "원피스", "귀멸의 칼날");
				// 첫 번째 사람만 결측
				case "-edge" -> compatibility(subcategoryId, "박하늘", PromptFixtures.personEdge(),
					"최서준", PromptFixtures.person2(), "", "");
				// 두 번째 사람도 결측. 궁합 빌더가 person2 쪽에서 타는 null 분기를 덮는다.
				case "-edge2" -> compatibility(subcategoryId, "박하늘", PromptFixtures.personEdge(),
					"최서준", PromptFixtures.personEdge(), "", "");
				default -> throw new IllegalArgumentException("알 수 없는 변이: " + variant);
			};
			case "free" -> switch (variant) {
				case "" -> free(subcategoryId, "김태우", PromptFixtures.person1());
				case "-edge" -> free(subcategoryId, "박하늘", PromptFixtures.personEdge());
				case "-reverse" ->
					free(subcategoryId, "강민호", PromptFixtures.personReverseDaewoon());
				default -> throw new IllegalArgumentException("알 수 없는 변이: " + variant);
			};
			default -> throw new IllegalArgumentException("알 수 없는 종류: " + kind);
		};

		String expected = readExpectedFile(subcategoryId + variant + ".txt");

		assertThat(actual)
			.as("%s %s%s 프롬프트", kind, subcategoryId, variant)
			.isEqualTo(expected);
	}

	private static String readExpectedFile(String fileName) {
		try (InputStream in = PromptMatchesExpectedFileTest.class.getClassLoader()
			.getResourceAsStream("expected-prompts/" + fileName)) {
			if (in == null) {
				throw new IllegalStateException("기대 결과 파일이 없습니다: " + fileName);
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
