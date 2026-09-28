package com.mansereok.server.domain.interpret.product;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 해석 상품 목록. 상품 번호(subcategories.id)마다 어떤 프롬프트로 풀고 결과를 어느 표에 만드는지를 여기 한 곳에 둔다.
 *
 * <p>예전에는 같은 번호 목록이 결과 생성(ResultService), 두 프롬프트 팩터리, 후처리 표에 숫자로 따로 적혀 있어서 한 곳만
 * 고쳐도 컴파일러가 알려 주지 않았다. 프롬프트 팩터리는 이 열거 타입에 대해 default 없는 switch 를 쓰므로, 상수를 더하면
 * 팩터리가 그 상품을 어떻게 풀지 정할 때까지 컴파일되지 않는다. 후처리 표는 애플리케이션이 뜰 때 모든 상수를 빠짐없이
 * 다루는지 확인한다.
 *
 * <p>상수 이름은 그 상품의 프롬프트를 만드는 메서드 이름에서 따왔다. 러브 스토리 프롬프트를 함께 쓰는 4·6·14 는 코드에
 * 상품 이름이 없어 번호로 구분한다. 번호는 운영 DB 의 subcategories.id 와 맞물려 있으므로 바꾸지 않는다.
 */
public enum InterpretationProduct {

	LIFE_OVERALL(1, Kind.SAJU, ResultTable.RESULTS),
	PERSONALITY_ANALYSIS(2, Kind.SAJU, ResultTable.RESULTS),
	CAREER_APTITUDE(3, Kind.SAJU, ResultTable.RESULTS),
	LOVE_STORY_4(4, Kind.COMPATIBILITY, ResultTable.COMPATIBILITY_RESULTS),
	IDOL_ANALYSIS(5, Kind.SAJU, ResultTable.RESULTS),
	LOVE_STORY_6(6, Kind.COMPATIBILITY, ResultTable.COMPATIBILITY_RESULTS),
	IDOL_COMPATIBILITY(7, Kind.COMPATIBILITY, ResultTable.COMPATIBILITY_RESULTS),
	/**
	 * 삼각관계. 궁합 프롬프트는 있지만 결과는 예전부터 사주 결과 표(results)에 만들어 왔다. 이 상태로 판매되면 궁합 해석
	 * 요청이 궁합 결과를 찾지 못해 404 로 끝난다. 운영 DB 에서 판매 이력이 없다고 확인되면 COMPATIBILITY_RESULTS 로 바꾼다.
	 */
	TRIANGLE_RELATIONSHIP(8, Kind.COMPATIBILITY, ResultTable.RESULTS),
	CHARACTER_SAJU(9, Kind.SAJU, ResultTable.RESULTS),
	CHARACTER_COMPATIBILITY(10, Kind.COMPATIBILITY, ResultTable.COMPATIBILITY_RESULTS),
	CHARACTER_TO_CHARACTER_COMPATIBILITY(11, Kind.COMPATIBILITY, ResultTable.COMPATIBILITY_RESULTS),
	ACTOR_ANALYSIS(13, Kind.SAJU, ResultTable.RESULTS),
	LOVE_STORY_14(14, Kind.COMPATIBILITY, ResultTable.COMPATIBILITY_RESULTS),
	ACTOR_COMPATIBILITY(15, Kind.COMPATIBILITY, ResultTable.COMPATIBILITY_RESULTS),
	LOVE_LUCK(17, Kind.SAJU, ResultTable.RESULTS),
	NEW_YEAR_2026(18, Kind.SAJU, ResultTable.RESULTS),
	REUNION(19, Kind.COMPATIBILITY, ResultTable.COMPATIBILITY_RESULTS),
	MONEY_LUCK(20, Kind.SAJU, ResultTable.RESULTS),
	BUSINESS_LUCK(21, Kind.SAJU, ResultTable.RESULTS),
	ACADEMIC_LUCK(22, Kind.SAJU, ResultTable.RESULTS),
	LIFE_ADVICE(23, Kind.SAJU, ResultTable.RESULTS),

	CHANGES_2026(101, Kind.FREE_FORTUNE, ResultTable.RESULTS),
	KEYWORD_2026(102, Kind.FREE_FORTUNE, ResultTable.RESULTS),
	FLIRTING(103, Kind.FREE_FORTUNE, ResultTable.RESULTS),
	CHEMISTRY_MATCH(104, Kind.FREE_FORTUNE, ResultTable.RESULTS),
	TODAY_FORTUNE(105, Kind.FREE_FORTUNE, ResultTable.RESULTS),
	MARCH_MONTHLY_FORTUNE(106, Kind.FREE_FORTUNE, ResultTable.RESULTS);

	/** 상품을 어느 프롬프트 팩터리 메서드로 푸는지. */
	public enum Kind {
		/** 한 사람의 사주를 푸는 상품. SajuPromptFactory.create 가 맡는다. */
		SAJU,
		/** 두 사람의 궁합을 푸는 상품. CompatibilityPromptFactory.create 가 맡는다. */
		COMPATIBILITY,
		/** 한 사람의 무료 운세 상품. SajuPromptFactory.createFree 가 맡는다. */
		FREE_FORTUNE
	}

	/** 결제 확정 때 첫 결과 행을 만드는 표. */
	public enum ResultTable {
		/** 사주 결과 표(Result). */
		RESULTS,
		/** 궁합 결과 표(CompatibilityResult). */
		COMPATIBILITY_RESULTS
	}

	// 번호가 겹치면 toUnmodifiableMap 이 IllegalStateException 을 던져 클래스가 올라오지 않는다.
	private static final Map<Long, InterpretationProduct> BY_ID = Arrays.stream(values())
		.collect(Collectors.toUnmodifiableMap(InterpretationProduct::id, Function.identity()));

	private final long id;
	private final Kind kind;
	private final ResultTable resultTable;

	InterpretationProduct(long id, Kind kind, ResultTable resultTable) {
		this.id = id;
		this.kind = kind;
		this.resultTable = resultTable;
	}

	/**
	 * 상품 번호로 상품을 찾는다. 목록에 없는 번호면 빈 Optional 이다.
	 */
	public static Optional<InterpretationProduct> find(long id) {
		return Optional.ofNullable(BY_ID.get(id));
	}

	/**
	 * 상품 번호로 상품을 찾는다. 요청 경로에서 받은 번호를 상품으로 바꿀 때 쓴다.
	 *
	 * @throws IllegalArgumentException id 가 null 이거나 목록에 없는 번호일 때
	 */
	public static InterpretationProduct require(Long id) {
		if (id == null) {
			throw unsupported(null);
		}
		return find(id).orElseThrow(() -> unsupported(id));
	}

	/**
	 * 이 상품을 맡지 않는 곳에 들어왔을 때 던질 예외. 목록에 없는 번호와 같은 문구를 쓴다.
	 */
	public static IllegalArgumentException unsupported(Long id) {
		return new IllegalArgumentException("지원하지 않는 카테고리입니다: " + id);
	}

	public long id() {
		return id;
	}

	public Kind kind() {
		return kind;
	}

	public ResultTable resultTable() {
		return resultTable;
	}

	/** 두 사람의 궁합을 푸는 상품인지. */
	public boolean isCompatibility() {
		return kind == Kind.COMPATIBILITY;
	}

	/** 무료 운세 프롬프트(SajuPromptFactory.createFree)로 푸는 상품인지. */
	public boolean usesFreePrompt() {
		return kind == Kind.FREE_FORTUNE;
	}
}
