package com.mansereok.server.domain.interpret.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
	void valueFallbacks() {
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
}
