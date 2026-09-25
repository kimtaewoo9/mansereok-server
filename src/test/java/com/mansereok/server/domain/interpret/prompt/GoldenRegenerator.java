package com.mansereok.server.domain.interpret.prompt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * 골든 파일을 현재 구현의 출력으로 다시 뜨는 일회용 도구. 평소에는 꺼 두고,
 * 프롬프트를 의도적으로 바꾼 PR 에서만 {@code @Disabled} 를 떼고 한 번 돌린다.
 *
 * <p>시각에 따라 달라지는 값만 자리표시자로 되돌린다. 연도는 상품에 따라 다르게 다뤄야 한다.
 * 기준연도를 {@code LocalDate.now()} 에서 가져오는 상품(궁합 전체)은 해가 바뀌면 값이 달라지므로
 * 반드시 자리표시자로 되돌린다. 반대로 2026 이 코드에 박혀 있는 상품(18, 101, 102, 106)은
 * 되돌리면 안 된다. 자리표시자가 "오늘의 연도" 로 펼쳐지는 탓에 2027년에 골든이 깨지기 때문이다.
 */
@Disabled("골든을 의도적으로 다시 뜰 때만 수동으로 켠다")
class GoldenRegenerator {

	private static final Path GOLDEN_DIR = Path.of("src/test/resources/prompt-golden");

	@Test
	void regenerateNewVariants() throws Exception {
		// 18, 101, 106 은 기준연도 2026 이 코드에 박혀 있어 연도를 되돌리지 않는다.
		write("18-reverse.txt", SajuPrompts.saju(18L, "강민호",
			PromptFixtures.personReverseDaewoon(), "원피스"), false);
		write("101-reverse.txt", SajuPrompts.free(101L, "강민호",
			PromptFixtures.personReverseDaewoon()), false);
		write("106-reverse.txt", SajuPrompts.free(106L, "강민호",
			PromptFixtures.personReverseDaewoon()), false);
		// 궁합은 기준연도를 오늘에서 가져오므로 연도를 되돌린다.
		for (long id : new long[]{4L, 6L, 7L, 8L, 10L, 11L, 14L, 15L, 19L}) {
			write(id + "-edge2.txt", SajuPrompts.compatibility(id, "박하늘",
				PromptFixtures.personEdge(), "최서준", PromptFixtures.personEdge(), "", ""), true);
		}
	}

	private void write(String fileName, String prompt, boolean maskYear) throws Exception {
		Files.writeString(GOLDEN_DIR.resolve(fileName), maskTimeDependentValues(prompt, maskYear),
			StandardCharsets.UTF_8);
	}

	/**
	 * {@code PromptGoldenTest.expandTimeDependentValues} 의 역방향.
	 * {@code maskYear} 는 기준연도를 오늘에서 가져오는 상품에만 켠다.
	 */
	private static String maskTimeDependentValues(String prompt, boolean maskYear) {
		LocalDate todayInKst = LocalDate.now(ZoneId.of("Asia/Seoul"));
		LocalDate today = LocalDate.now();
		DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy년 MM월 dd일");
		String dayOfWeek = todayInKst.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN);

		String masked = prompt
			.replace(todayInKst.format(formatter) + " " + dayOfWeek, "<<TODAY_WITH_DOW>>")
			.replace(DaewoonSections.calculateTodayDayPillar(todayInKst), "<<TODAY_ILJU>>")
			.replace(today.format(formatter), "<<TODAY>>");
		return maskYear
			? masked.replace("현재 " + today.getYear() + "년", "현재 <<THIS_YEAR>>년")
			: masked;
	}

	/** 골든 테스트와 같은 방식으로 프롬프트를 만든다. */
	private static final class SajuPrompts {

		private static final SajuPromptFactory SAJU = new SajuPromptFactory();
		private static final CompatibilityPromptFactory COMPAT = new CompatibilityPromptFactory();

		static String saju(Long id, String name,
			com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse r,
			String sourceTitle) {
			return SAJU.create(id, PromptContext.of(name, r, sourceTitle));
		}

		static String free(Long id, String name,
			com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse r) {
			return SAJU.createFree(id, PromptContext.of(name, r));
		}

		static String compatibility(Long id, String n1,
			com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse r1,
			String n2,
			com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse r2,
			String t1, String t2) {
			return COMPAT.create(id, CompatibilityPromptContext.of(n1, r1, t1, n2, r2, t2));
		}
	}
}
