package com.mansereok.server.domain.interpret.calculator;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 네 기둥의 라벨. 응답의 신살 키와 프롬프트가 이 라벨을 함께 쓴다.
 */
@DisplayName("사주 기둥")
class PillarTest {

	@Test
	@DisplayName("라벨은 년주·월주·일주·시주 이고 선언 순서도 같다")
	void labelsInPillarOrder() {
		// when
		Pillar[] pillars = Pillar.values();

		// then
		assertThat(pillars).extracting(Pillar::label).containsExactly("년주", "월주", "일주", "시주");
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@EnumSource(Pillar.class)
	@DisplayName("라벨로 찾으면 그 기둥이 나온다")
	void findsPillarByItsLabel(Pillar pillar) {
		// when
		Optional<Pillar> found = Pillar.fromLabel(pillar.label());

		// then
		assertThat(found).contains(pillar);
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@NullAndEmptySource
	@ValueSource(strings = {"시지", "YEAR", "년주 "})
	@DisplayName("모르는 라벨이면 예외 없이 빈 값을 돌려준다")
	void returnsEmptyForUnknownLabel(String label) {
		// when
		Optional<Pillar> found = Pillar.fromLabel(label);

		// then
		assertThat(found).isEmpty();
	}
}
