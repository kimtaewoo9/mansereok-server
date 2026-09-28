package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 프롬프트에서 값이 비었을 때 쓰던 "?" 와 "-" 표기를 모아 둔 헬퍼. 표기가 달라지면
 * 프롬프트 문자열이 통째로 달라지므로 기본값을 못박아 둔다.
 */
@DisplayName("사주 값 표기 헬퍼")
class SajuElementSectionsTest {

	@Test
	@DisplayName("기둥이 없으면 한글 표기는 물음표가 된다")
	void koreanOrUnknownFallsBackToQuestionMark() {
		PillarElement pillar = PillarElement.builder().korean("갑").build();

		assertThat(SajuElementSections.koreanOrUnknown(pillar)).isEqualTo("갑");
		assertThat(SajuElementSections.koreanOrUnknown(null)).isEqualTo("?");
	}

	@Test
	@DisplayName("값이 없으면 물음표, 줄표 기본값을 쓴다")
	void usesQuestionMarkOrDashWhenValueMissing() {
		assertThat(SajuElementSections.orUnknown("목")).isEqualTo("목");
		assertThat(SajuElementSections.orUnknown(null)).isEqualTo("?");
		assertThat(SajuElementSections.orDash("건록")).isEqualTo("건록");
		assertThat(SajuElementSections.orDash(null)).isEqualTo("-");
	}

	@Test
	@DisplayName("12운성은 기둥이 없거나 값이 없으면 줄표가 된다")
	void unseongOrDashFallsBackToDash() {
		assertThat(SajuElementSections.unseongOrDash(
			PillarElement.builder().unseong("건록").build())).isEqualTo("건록");
		assertThat(SajuElementSections.unseongOrDash(
			PillarElement.builder().build())).isEqualTo("-");
		assertThat(SajuElementSections.unseongOrDash(null)).isEqualTo("-");
	}

	@Nested
	@DisplayName("지장간 줄은")
	class JijangganLine {

		@Test
		@DisplayName("값이 있는 칸만 순서대로 '한글이름오행(십성,비율%)' 로 적고 빈 칸은 건너뛴다")
		void writesPresentSlotsInOrder() {
			// given: 두 번째 칸이 비어 있다
			JijangganInfo jijanggan = JijangganInfo.builder()
				.first(hiddenStem("임", "수", "비견", 30))
				.third(hiddenStem("계", "수", "겁재", 70))
				.build();

			// when
			String line = jijangganLine(jijanggan);

			// then
			assertThat(line).isEqualTo("- 년지(자): 임수(비견,30%), 계수(겁재,70%)\n");
		}

		@Test
		@DisplayName("칸 안의 한글 이름·오행·십성·비율이 비어 있으면 'null' 대신 물음표로 적는다")
		void writesQuestionMarkForMissingValues() {
			// given
			JijangganInfo jijanggan = JijangganInfo.builder()
				.first(hiddenStem(null, null, null, null))
				.build();

			// when
			String line = jijangganLine(jijanggan);

			// then
			assertThat(line).isEqualTo("- 년지(자): ??(?,?)\n");
			assertThat(line).doesNotContain("null");
		}

		private String jijangganLine(JijangganInfo jijanggan) {
			StringBuilder prompt = new StringBuilder();
			SajuElementSections.appendJijangganLine(prompt, "년지",
				PillarElement.builder().korean("자").jijanggan(jijanggan).build());
			return prompt.toString();
		}
	}

	/**
	 * 지장간 십성은 비율을 100 으로 나눠 반올림한 만큼 센다. 그래서 비율 50 미만은 0 개다.
	 */
	@ParameterizedTest(name = "[{index}] 지장간 비율 {0} → 정관 {1}개")
	@DisplayName("지장간 십성은 비율(rate)이 50 이상일 때만 1개로 센다")
	@CsvSource(textBlock = """
		# 지장간 비율, 정관 개수
		30, 0
		49, 0
		50, 1
		70, 1
		""")
	void countsHiddenStemTenStarFromHalfRate(int rate, int expectedCount) {
		// given: 지지 하나에 정관 지장간 한 칸만 둔다. 지지 자신의 십성은 편재라 정관 개수에 섞이지 않는다
		SajuInfo saju = SajuInfo.builder()
			.yearGround(PillarElement.builder().fiveCircle("수").tenStar("편재")
				.jijanggan(JijangganInfo.builder().first(hiddenStem("신", "금", "정관", rate)).build())
				.build())
			.build();

		// when
		ElementDistribution distribution = SajuElementSections.calculateDistributionWithJijanggan(saju);

		// then
		assertThat(distribution.tenStarCounts().getOrDefault("정관", 0)).isEqualTo(expectedCount);
	}

	private static JijangganElement hiddenStem(String korean, String fiveCircle, String tenStar, Integer rate) {
		return JijangganElement.builder().korean(korean).fiveCircle(fiveCircle).tenStar(tenStar).rate(rate).build();
	}
}
