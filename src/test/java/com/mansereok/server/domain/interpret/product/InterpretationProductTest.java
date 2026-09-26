package com.mansereok.server.domain.interpret.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.interpret.product.InterpretationProduct.Kind;
import com.mansereok.server.domain.interpret.product.InterpretationProduct.ResultTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("해석 상품 목록")
class InterpretationProductTest {

	/**
	 * 번호가 겹치면 이 단언까지 오지 못한다. 번호로 상품을 찾는 표(BY_ID)를 만들다 클래스 로딩이 먼저 실패해
	 * ExceptionInInitializerError 로 끝난다. 어느 쪽이든 테스트는 실패하므로 겹침은 잡힌다.
	 */
	@Test
	@DisplayName("상품 번호는 서로 겹치지 않는다")
	void idsAreUnique() {
		assertThat(InterpretationProduct.values())
			.extracting(InterpretationProduct::id)
			.doesNotHaveDuplicates();
	}

	/**
	 * 번호는 운영 DB 의 subcategories.id 와 맞물려 있다. 상수를 옮기거나 번호를 고치면 다른 상품의 프롬프트로 풀리거나 결과가
	 * 엉뚱한 표에 만들어지므로, 번호마다 상품·종류·결과 표를 값 그대로 적어 둔다.
	 */
	@ParameterizedTest(name = "[{index}] {0}번 → {1}, {2}, {3}")
	@CsvSource(textBlock = """
		# 번호, 상품,                                  종류,          결과 표
		1,   LIFE_OVERALL,                         SAJU,          RESULTS
		2,   PERSONALITY_ANALYSIS,                 SAJU,          RESULTS
		3,   CAREER_APTITUDE,                      SAJU,          RESULTS
		4,   LOVE_STORY_4,                         COMPATIBILITY, COMPATIBILITY_RESULTS
		5,   IDOL_ANALYSIS,                        SAJU,          RESULTS
		6,   LOVE_STORY_6,                         COMPATIBILITY, COMPATIBILITY_RESULTS
		7,   IDOL_COMPATIBILITY,                   COMPATIBILITY, COMPATIBILITY_RESULTS
		# 8 은 궁합 상품이지만 운영 판매 이력을 확인하기 전까지 예전처럼 사주 결과 표에 만든다
		8,   TRIANGLE_RELATIONSHIP,                COMPATIBILITY, RESULTS
		9,   CHARACTER_SAJU,                       SAJU,          RESULTS
		10,  CHARACTER_COMPATIBILITY,              COMPATIBILITY, COMPATIBILITY_RESULTS
		11,  CHARACTER_TO_CHARACTER_COMPATIBILITY, COMPATIBILITY, COMPATIBILITY_RESULTS
		13,  ACTOR_ANALYSIS,                       SAJU,          RESULTS
		14,  LOVE_STORY_14,                        COMPATIBILITY, COMPATIBILITY_RESULTS
		15,  ACTOR_COMPATIBILITY,                  COMPATIBILITY, COMPATIBILITY_RESULTS
		17,  LOVE_LUCK,                            SAJU,          RESULTS
		18,  NEW_YEAR_2026,                        SAJU,          RESULTS
		19,  REUNION,                              COMPATIBILITY, COMPATIBILITY_RESULTS
		20,  MONEY_LUCK,                           SAJU,          RESULTS
		21,  BUSINESS_LUCK,                        SAJU,          RESULTS
		22,  ACADEMIC_LUCK,                        SAJU,          RESULTS
		23,  LIFE_ADVICE,                          SAJU,          RESULTS
		101, CHANGES_2026,                         FREE_FORTUNE,  RESULTS
		102, KEYWORD_2026,                         FREE_FORTUNE,  RESULTS
		103, FLIRTING,                             FREE_FORTUNE,  RESULTS
		104, CHEMISTRY_MATCH,                      FREE_FORTUNE,  RESULTS
		105, TODAY_FORTUNE,                        FREE_FORTUNE,  RESULTS
		106, MARCH_MONTHLY_FORTUNE,                FREE_FORTUNE,  RESULTS
		""")
	@DisplayName("번호마다 상품과 그 종류, 결과를 만들 표가 정해져 있다")
	void idDecidesProductKindAndResultTable(long id, InterpretationProduct expected, Kind kind,
		ResultTable resultTable) {
		// when
		InterpretationProduct product = InterpretationProduct.require(id);

		// then
		assertThat(product).isEqualTo(expected);
		assertThat(product.kind()).as("종류").isEqualTo(kind);
		assertThat(product.resultTable()).as("결과 표").isEqualTo(resultTable);
	}

	@ParameterizedTest(name = "[{index}] {0} → 궁합 {1}, 무료 운세 프롬프트 {2}")
	@CsvSource(textBlock = """
		# 상품,                  궁합 상품인가, 무료 운세 프롬프트로 푸는가
		LIFE_OVERALL,          false,         false
		REUNION,               true,          false
		# 결과를 사주 표에 만들어도 종류는 궁합이다
		TRIANGLE_RELATIONSHIP, true,          false
		CHANGES_2026,          false,         true
		""")
	@DisplayName("궁합 상품인지와 무료 운세 프롬프트로 푸는지는 종류로 정해진다")
	void kindAnswersCompatibilityAndFreePrompt(InterpretationProduct product, boolean compatibility,
		boolean freePrompt) {
		assertThat(product.isCompatibility()).as("궁합 상품").isEqualTo(compatibility);
		assertThat(product.usesFreePrompt()).as("무료 운세 프롬프트").isEqualTo(freePrompt);
	}

	@Nested
	@DisplayName("번호로 찾을 때")
	class Find {

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(InterpretationProduct.class)
		@DisplayName("상품의 번호로 찾으면 그 상품이 나온다")
		void findsProductByItsOwnId(InterpretationProduct product) {
			assertThat(InterpretationProduct.find(product.id())).contains(product);
		}

		/**
		 * 4294967297 은 int 로 자르면 1(인생 총운), 4294967305 는 9(캐릭터 사주)가 된다. 번호를 int 로 좁히지 않으므로
		 * 다른 상품으로 잘려 들어가지 않는다.
		 */
		@ParameterizedTest(name = "[{index}] {0}")
		@ValueSource(longs = {0, -1, 12, 16, 24, 100, 107, 4294967297L, 4294967305L})
		@DisplayName("목록에 없는 번호로 찾으면 빈 값이다")
		void findsNothingForIdOutsideList(long id) {
			assertThat(InterpretationProduct.find(id)).isEmpty();
		}

		@Test
		@DisplayName("require 는 목록에 있는 번호면 그 상품을 돌려준다")
		void requireReturnsProduct() {
			assertThat(InterpretationProduct.require(19L)).isEqualTo(InterpretationProduct.REUNION);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@CsvSource(textBlock = """
			# 번호,       예외 메시지
			12,         지원하지 않는 카테고리입니다: 12
			4294967297, 지원하지 않는 카테고리입니다: 4294967297
			""")
		@DisplayName("require 는 목록에 없는 번호면 그 번호를 담은 IllegalArgumentException 을 던진다")
		void requireRejectsIdOutsideList(long id, String message) {
			assertThatThrownBy(() -> InterpretationProduct.require(id))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage(message);
		}

		@Test
		@DisplayName("require 는 번호가 null 이면 IllegalArgumentException 을 던진다")
		void requireRejectsNull() {
			assertThatThrownBy(() -> InterpretationProduct.require(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("지원하지 않는 카테고리입니다: null");
		}
	}
}
