package com.mansereok.server.domain.interpret.calculator;

/**
 * 오행(목·화·토·금·수). 오행마다 붙는 화면 색, 용신 행운색·방향과 생(生)·극(剋) 관계를 한 곳에 둔다.
 *
 * <p>선언 순서가 목·화·토·금·수라 EnumMap 이나 values() 를 돌면 늘 이 순서로 나온다. 프롬프트의 오행 줄 순서도 여기서 정해진다.
 */
public enum FiveElement {
	WOOD("목", "#4CAF50", "청색, 녹색", "동쪽"),
	FIRE("화", "#F44336", "적색, 분홍", "남쪽"),
	EARTH("토", "#FFD600", "황색, 베이지", "중앙, 거주지 근처"),
	METAL("금", "#E0E0E0", "백색, 은색", "서쪽"),
	WATER("수", "#039BE5", "검정, 남색", "북쪽");

	private final String korean;
	private final String color;
	private final String luckyColor;
	private final String luckyDirection;

	FiveElement(String korean, String color, String luckyColor, String luckyDirection) {
		this.korean = korean;
		this.color = color;
		this.luckyColor = luckyColor;
		this.luckyDirection = luckyDirection;
	}

	/**
	 * 한글 이름("목")으로 오행을 찾는다. 만세력 계산이 채우는 오행 칸은 늘 한글 한 글자라, 다른 값(한자 "木", 빈 값)이 오면 서버 데이터가
	 * 어긋난 것이다. 빈 색이나 "-" 로 조용히 넘기지 않고 바로 알린다.
	 *
	 * @throws IllegalStateException 목·화·토·금·수 가 아닐 때
	 */
	public static FiveElement of(String korean) {
		for (FiveElement element : values()) {
			if (element.korean.equals(korean)) {
				return element;
			}
		}
		throw new IllegalStateException("오행 이름이 목·화·토·금·수 가 아닙니다: " + korean);
	}

	/**
	 * 이 오행이 생하는 오행(목 → 화 → 토 → 금 → 수 → 목). 일간 기준으로는 식상이다.
	 */
	public FiveElement generates() {
		return switch (this) {
			case WOOD -> FIRE;
			case FIRE -> EARTH;
			case EARTH -> METAL;
			case METAL -> WATER;
			case WATER -> WOOD;
		};
	}

	/**
	 * 이 오행을 생해 주는 오행. 일간 기준으로는 인성이다.
	 */
	public FiveElement generatedBy() {
		return switch (this) {
			case WOOD -> WATER;
			case FIRE -> WOOD;
			case EARTH -> FIRE;
			case METAL -> EARTH;
			case WATER -> METAL;
		};
	}

	/**
	 * 이 오행이 극하는 오행(목 → 토 → 수 → 화 → 금 → 목). 일간 기준으로는 재성이다.
	 */
	public FiveElement controls() {
		return switch (this) {
			case WOOD -> EARTH;
			case FIRE -> METAL;
			case EARTH -> WATER;
			case METAL -> WOOD;
			case WATER -> FIRE;
		};
	}

	/**
	 * 이 오행을 극하는 오행. 일간 기준으로는 관살이다.
	 */
	public FiveElement controlledBy() {
		return switch (this) {
			case WOOD -> METAL;
			case FIRE -> WATER;
			case EARTH -> WOOD;
			case METAL -> FIRE;
			case WATER -> EARTH;
		};
	}

	/** 한글 이름. 응답 JSON 과 프롬프트에 쓰는 값이다. */
	public String korean() {
		return korean;
	}

	/** 기둥·지장간 글자를 칠하는 화면 색. */
	public String color() {
		return color;
	}

	/** 이 오행이 용신일 때 알려 주는 행운색. */
	public String luckyColor() {
		return luckyColor;
	}

	/** 이 오행이 용신일 때 알려 주는 행운 방향. */
	public String luckyDirection() {
		return luckyDirection;
	}
}
