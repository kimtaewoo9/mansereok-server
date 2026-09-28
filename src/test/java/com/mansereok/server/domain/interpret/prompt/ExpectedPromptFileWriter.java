package com.mansereok.server.domain.interpret.prompt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * 기대 결과 파일을 현재 구현의 출력으로 다시 만드는 일회용 도구. 평소에는 꺼 두고,
 * 프롬프트를 의도적으로 바꾼 PR 에서만 {@code @Disabled} 를 떼고 한 번 돌린다.
 *
 * <p>기대 결과 비교 테스트와 같은 고정 시계({@link PromptFixtures#FIXED_CLOCK})로 프롬프트를 만들어 가공 없이 그대로 쓴다.
 * 오늘 날짜, 오늘 일진, 현재 연도가 그 시각으로 정해지므로 자리표시자로 되돌릴 값이 없다.
 */
@Disabled("기대 결과 파일을 의도적으로 다시 만들 때만 수동으로 켠다")
class ExpectedPromptFileWriter {

	private static final Path EXPECTED_PROMPTS_DIR = Path.of("src/test/resources/expected-prompts");

	@Test
	void writeExpectedFilesForAddedVariants() throws Exception {
		writeExpectedFile("18-reverse.txt", SajuPrompts.saju(18L, "강민호",
			PromptFixtures.personReverseDaewoon(), "원피스"));
		writeExpectedFile("101-reverse.txt", SajuPrompts.free(101L, "강민호",
			PromptFixtures.personReverseDaewoon()));
		writeExpectedFile("106-reverse.txt", SajuPrompts.free(106L, "강민호",
			PromptFixtures.personReverseDaewoon()));
		for (long id : new long[]{4L, 6L, 7L, 8L, 10L, 11L, 14L, 15L, 19L}) {
			writeExpectedFile(id + "-edge2.txt", SajuPrompts.compatibility(id, "박하늘",
				PromptFixtures.personEdge(), "최서준", PromptFixtures.personEdge(), "", ""));
		}
		// 출생시간을 모르는 사람
		writeExpectedFile("1-time-unknown.txt", SajuPrompts.saju(1L, "김태우",
			PromptFixtures.personTimeUnknown(), "원피스"));
	}

	private void writeExpectedFile(String fileName, String prompt) throws Exception {
		Files.writeString(EXPECTED_PROMPTS_DIR.resolve(fileName), prompt, StandardCharsets.UTF_8);
	}

	/** 기대 결과 비교 테스트와 같은 방식으로 프롬프트를 만든다. */
	private static final class SajuPrompts {

		private static final SajuPromptFactory SAJU = new SajuPromptFactory(PromptFixtures.FIXED_CLOCK);
		private static final CompatibilityPromptFactory COMPAT =
			new CompatibilityPromptFactory(PromptFixtures.FIXED_CLOCK);

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
