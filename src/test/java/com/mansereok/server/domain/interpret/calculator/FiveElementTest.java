package com.mansereok.server.domain.interpret.calculator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 오행 다섯 개의 이름·색·행운색·방향과 생(生)·극(剋) 관계를 값 그대로 고정한다.
 *
 * <p>색은 /calculate 응답의 five_circle_color, 행운색·방향은 용신 설명 문장에 그대로 나가므로 한 글자라도 바뀌면 실패해야 한다.
 */
@DisplayName("오행")
class FiveElementTest {

	@ParameterizedTest(name = "[{index}] {1} → {0}, 색 {2}, 행운색 {3}, 방향 {4}")
	@DisplayName("오행마다 한글 이름·화면 색·행운색·행운 방향이 정해져 있다")
	@CsvSource(textBlock = """
		# 오행, 한글 이름, 화면 색, 행운색, 행운 방향
		WOOD,  목, #4CAF50, '청색, 녹색',   동쪽
		FIRE,  화, #F44336, '적색, 분홍',   남쪽
		EARTH, 토, #FFD600, '황색, 베이지', '중앙, 거주지 근처'
		METAL, 금, #E0E0E0, '백색, 은색',   서쪽
		WATER, 수, #039BE5, '검정, 남색',   북쪽
		""")
	void hasFixedNameColorAndLuck(FiveElement element, String korean, String color, String luckyColor,
		String luckyDirection) {
		// when
		FiveElement found = FiveElement.of(korean);

		// then
		assertThat(found).isEqualTo(element);
		assertThat(found.korean()).isEqualTo(korean);
		assertThat(found.color()).isEqualTo(color);
		assertThat(found.luckyColor()).isEqualTo(luckyColor);
		assertThat(found.luckyDirection()).isEqualTo(luckyDirection);
	}

	@ParameterizedTest(name = "[{index}] {0}: 생 {1}, 생받음 {2}, 극 {3}, 극받음 {4}")
	@DisplayName("상생은 목→화→토→금→수→목, 상극은 목→토→수→화→금→목 이다")
	@CsvSource(textBlock = """
		# 오행, 생하는 오행, 생해 주는 오행, 극하는 오행, 극하는 쪽 오행
		WOOD,  FIRE,  WATER, EARTH, METAL
		FIRE,  EARTH, WOOD,  METAL, WATER
		EARTH, METAL, FIRE,  WATER, WOOD
		METAL, WATER, EARTH, WOOD,  FIRE
		WATER, WOOD,  METAL, FIRE,  EARTH
		""")
	void followsGenerationAndControlCycles(FiveElement element, FiveElement generates,
		FiveElement generatedBy, FiveElement controls, FiveElement controlledBy) {
		// when & then
		assertThat(element.generates()).as("생하는 오행").isEqualTo(generates);
		assertThat(element.generatedBy()).as("생해 주는 오행").isEqualTo(generatedBy);
		assertThat(element.controls()).as("극하는 오행").isEqualTo(controls);
		assertThat(element.controlledBy()).as("극하는 쪽 오행").isEqualTo(controlledBy);
	}

	@Test
	@DisplayName("목에서 생을 다섯 번 따라가면 다섯 오행을 한 번씩 지나 목으로 돌아온다")
	void generationCycleReturnsAfterFiveSteps() {
		// when
		FiveElement first = FiveElement.WOOD.generates();
		FiveElement second = first.generates();
		FiveElement third = second.generates();
		FiveElement fourth = third.generates();
		FiveElement fifth = fourth.generates();

		// then
		assertThat(new FiveElement[]{first, second, third, fourth})
			.containsExactly(FiveElement.FIRE, FiveElement.EARTH, FiveElement.METAL, FiveElement.WATER);
		assertThat(fifth).isEqualTo(FiveElement.WOOD);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@DisplayName("생해 주는 오행은 생하는 오행의 반대 방향이고, 극하는 쪽 오행은 극하는 오행의 반대 방향이다")
	@EnumSource(FiveElement.class)
	void reverseRelationsUndoForwardRelations(FiveElement element) {
		// when & then
		assertThat(element.generates().generatedBy()).isEqualTo(element);
		assertThat(element.generatedBy().generates()).isEqualTo(element);
		assertThat(element.controls().controlledBy()).isEqualTo(element);
		assertThat(element.controlledBy().controls()).isEqualTo(element);
	}

	@ParameterizedTest(name = "[{index}] \"{0}\"")
	@DisplayName("한글 목·화·토·금·수 가 아닌 이름(한자, 빈 값)이면 빈 색으로 넘기지 않고 IllegalStateException 을 던진다")
	@NullAndEmptySource
	@ValueSource(strings = {"木", "나무", " 목"})
	void rejectsUnknownName(String name) {
		// when & then
		assertThatThrownBy(() -> FiveElement.of(name))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("오행 이름이 목·화·토·금·수 가 아닙니다: " + name);
	}
}
