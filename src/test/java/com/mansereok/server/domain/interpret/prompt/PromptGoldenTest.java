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
 *
 * <p><b>유효기간 주의.</b> 대운 구간과 시작연도는 자리표시자가 아니라 숫자 그대로 박혀 있는데,
 * 기준연도만 자리표시자다. 기준연도가 "오늘의 연도" 인 상품은 해가 바뀌어 대운 구간이 넘어가는 순간
 * 골든이 깨진다. person1 은 2035년, person2 는 2038년에 대운이 다음 칸으로 넘어가므로
 * <b>2035년이 되기 전에 골든을 다시 떠야 한다.</b>
 *
 * <p>기준연도가 2026 으로 코드에 박혀 있는 상품(18, 101, 102, 106)의 기존 골든은 사정이 더 급하다.
 * 골든을 뜬 시점이 2026년이라 코드에 박힌 2026 까지 &lt;&lt;THIS_YEAR&gt;&gt; 로 가려졌고,
 * 이 자리표시자는 "오늘의 연도" 로 펼쳐지므로 <b>2027년 1월 1일에 그 네 상품의 골든이 깨진다.</b>
 * 이번에 추가한 -reverse 골든은 같은 실수를 되풀이하지 않으려고 그 자리를 2026 그대로 두었다.
 * 기존 54개를 다시 뜰 때 같은 방식으로 고치면 된다.
 *
 * <p>변이(mutation)로 확인한 덮는 범위:
 * <ul>
 *   <li>대운 역행 분기 - -reverse 골든이 덮는다. 음수 나머지 보정 {@code (x % 60 + 60) % 60} 의
 *       60 을 61 로 바꾸면 세 파일이 모두 깨진다.</li>
 *   <li>궁합 두 번째 사람의 결측 분기 - -edge2 골든이 덮는다.</li>
 *   <li>오늘 일진은 이 테스트가 프로덕션 함수로 기대값을 만들기 때문에 여기서는 잡히지 않는다.
 *       손으로 계산한 기대값은 {@link DaewoonSectionsTest} 가 들고 있다.</li>
 * </ul>
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

	/**
	 * 대운 역행 골든을 뜬 상품. 기준연도가 2026 으로 코드에 박혀 있어 해가 바뀌어도 대운 칸이
	 * 움직이지 않는 상품만 골랐다(18=신년운세, 101=2026 변화, 106=3월 월운).
	 * 역행 계산 자체는 상품과 무관한 한 곳(DaewoonSections)에서 하므로 27개 전부를 뜰 필요가 없다.
	 */
	private static final List<Long> REVERSE_SAJU_IDS = List.of(18L);
	private static final List<Long> REVERSE_FREE_IDS = List.of(101L, 106L);

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
		return builder.build();
	}

	@ParameterizedTest(name = "{0} {1}{2} 프롬프트는 골든 파일과 같다")
	@MethodSource("cases")
	void promptMatchesGolden(String kind, Long subcategoryId, String variant) {
		String actual = switch (kind) {
			case "saju" -> switch (variant) {
				case "" -> saju(subcategoryId, "김태우", PromptFixtures.person1(), "원피스");
				case "-edge" -> saju(subcategoryId, "박하늘", PromptFixtures.personEdge(), "");
				case "-reverse" ->
					saju(subcategoryId, "강민호", PromptFixtures.personReverseDaewoon(), "원피스");
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
