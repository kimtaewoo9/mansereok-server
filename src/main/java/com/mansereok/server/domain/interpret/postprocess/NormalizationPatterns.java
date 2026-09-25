package com.mansereok.server.domain.interpret.postprocess;

import java.util.regex.Pattern;

/**
 * 여러 후처리 규칙이 함께 쓰는 정규식. 한 규칙에서만 쓰는 정규식은 그 규칙 클래스가 직접 들고 있다.
 */
final class NormalizationPatterns {

	/** UI 페이지 구분 표시. 화면에서는 빈 줄 하나로 보여준다. */
	static final Pattern PAGE_BREAK = Pattern.compile("(?i)\\[\\s*PAGE_BREAK\\s*\\]");

	/** {@code [1. 성격 분석]} 처럼 요구하지 않은 대괄호 목차 제목. */
	static final Pattern BRACKET_SECTION_TITLE = Pattern.compile(
		"(?m)^\\s*\\[[0-9]+\\.[^\\]]*\\]\\s*\\n?");

	/** {@code 1-1 }, {@code 2.3 } 같은 하위 절 번호. */
	static final Pattern NUMBERED_SUBSECTION = Pattern.compile("(?m)^\\s*\\d+[-.]\\d+\\s+");

	/** {@code 1) }, {@code 2. } 같은 번호 목록 표시. */
	static final Pattern NUMBERED_LIST = Pattern.compile("(?m)^\\s*\\d+\\s*[-.)]\\s+");

	/** 마크다운 머리말({@code ##}). */
	static final Pattern HASH_HEADER = Pattern.compile("(?m)^\\s*#+\\s*");

	static final Pattern ISO_LOCAL_DATETIME_WITH_OPTIONAL_SECONDS = Pattern.compile(
		"(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2})(?::\\d{2})?");

	static final Pattern DATETIME_WITH_SPACE = Pattern.compile(
		"(\\d{4})-(\\d{2})-(\\d{2})\\s+(\\d{2}:\\d{2})(?::\\d{2})?");

	static final Pattern DATE_WITH_DAY = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

	static final Pattern YEAR_MONTH = Pattern.compile("\\b(\\d{4})-(0[1-9]|1[0-2])\\b");

	static final Pattern THREE_OR_MORE_NEWLINES = Pattern.compile("\\n{3,}");

	/** 연속된 줄바꿈. 문단 안에서는 한 칸 띄어쓰기로 바꾼다. */
	static final Pattern ONE_OR_MORE_NEWLINES = Pattern.compile("\\n+");

	/** 두 칸 이상 이어진 공백. */
	static final Pattern MULTI_SPACE = Pattern.compile("[ \\t]{2,}");

	/** 종류를 가리지 않는 연속 공백(줄바꿈 포함). */
	static final Pattern ANY_WHITESPACE = Pattern.compile("\\s+");

	private NormalizationPatterns() {
	}
}
