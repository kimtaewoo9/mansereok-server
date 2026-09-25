package com.mansereok.server.domain.interpret.text;

import java.util.Objects;

/**
 * 글자 수 상한에 맞춰 문자열 앞부분만 남기는 도우미.
 *
 * <p>상한은 {@link String#length()} 와 같은 기준(UTF-16 코드 유닛)으로 센다. 요청 DTO 의 {@code @Size} 도
 * 같은 기준이라 두 쪽의 상한이 어긋나지 않는다. 다만 자르는 위치는 코드 포인트 경계로 맞춘다.
 * 그냥 {@code substring} 하면 이모지 같은 보조 평면 문자의 서로게이트 쌍이 쪼개져 반쪽짜리 문자가 남는다.
 */
public final class TextCut {

	private TextCut() {
		throw new AssertionError("인스턴스를 만들 수 없는 유틸리티 클래스입니다.");
	}

	/**
	 * text 가 maxLength 보다 길면 앞에서부터 maxLength 코드 유닛 안쪽까지만 남긴다.
	 * 경계에 보조 평면 문자가 걸리면 그 문자는 통째로 빼므로 결과가 maxLength 보다 한 칸 짧을 수 있다.
	 * 앞뒤 공백 정리는 하지 않는다.
	 *
	 * @throws NullPointerException     text 가 null 일 때
	 * @throws IllegalArgumentException maxLength 가 음수일 때
	 */
	public static String atCodePointBoundary(String text, int maxLength) {
		Objects.requireNonNull(text, "text");
		if (maxLength < 0) {
			throw new IllegalArgumentException("maxLength 는 0 이상이어야 합니다: " + maxLength);
		}
		if (text.length() <= maxLength) {
			return text;
		}

		int end = maxLength;
		// text 가 maxLength 보다 길므로 charAt(end) 는 항상 있다.
		if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))
			&& Character.isLowSurrogate(text.charAt(end))) {
			end--;
		}
		return text.substring(0, end);
	}
}
