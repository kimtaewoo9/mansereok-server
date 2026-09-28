package com.mansereok.server.domain.interpret.calculator;

/**
 * 네 기둥의 천간·지지 한자 여덟 글자. 일간(日干)은 {@link #dayStem()} 하나로만 쓴다.
 *
 * <p>출생시간을 모르면 시주를 계산하지 않으므로 {@code timeStem}·{@code timeBranch} 는 null 이다.
 *
 * @param yearStem    년간 (예: "甲")
 * @param yearBranch  년지 (예: "午")
 * @param monthStem   월간 (예: "丙")
 * @param monthBranch 월지 (예: "巳")
 * @param dayStem     일간 (예: "壬")
 * @param dayBranch   일지 (예: "子")
 * @param timeStem    시간 (예: "丁", 출생시간을 모르면 null)
 * @param timeBranch  시지 (예: "未", 출생시간을 모르면 null)
 */
public record FourPillars(
	String yearStem,
	String yearBranch,
	String monthStem,
	String monthBranch,
	String dayStem,
	String dayBranch,
	String timeStem,
	String timeBranch
) {

}
