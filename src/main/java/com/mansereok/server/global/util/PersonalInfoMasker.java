package com.mansereok.server.global.util;

/**
 * 응답이나 로그에 개인정보를 원문 그대로 내보내지 않도록 일부 글자를 '*' 로 가린다.
 *
 * <p>글자 수는 UTF-16 문자(char)가 아니라 코드 포인트로 센다. 그래서 한자 확장 영역이나 이모지처럼 char 두 개로 된
 * 글자도 한 글자로 가려지고, 반쪽짜리 문자가 응답에 섞이지 않는다.
 */
public final class PersonalInfoMasker {

	private static final String MASK = "*";

	private PersonalInfoMasker() {
		throw new AssertionError("인스턴스를 만들지 않는 유틸리티 클래스입니다.");
	}

	/**
	 * 이름에서 첫 글자와 마지막 글자만 남기고 가운데를 가린다. 앞뒤 공백은 떼고 센다.
	 *
	 * <ul>
	 *   <li>한 글자: 전부 가린다. 예) 김 → *</li>
	 *   <li>두 글자: 첫 글자만 남긴다. 예) 이준 → 이*</li>
	 *   <li>세 글자 이상: 첫 글자와 마지막 글자만 남기고 가운데 글자 수만큼 가린다. 예) 홍길동 → 홍*동, 남궁민수 → 남**수</li>
	 * </ul>
	 *
	 * @return 가린 이름. null 이면 null, 공백뿐이면 빈 문자열
	 */
	public static String maskName(String name) {
		if (name == null) {
			return null;
		}
		int[] codePoints = name.strip().codePoints().toArray();
		int length = codePoints.length;
		if (length == 0) {
			return "";
		}
		if (length == 1) {
			return MASK;
		}
		StringBuilder masked = new StringBuilder().appendCodePoint(codePoints[0]);
		if (length == 2) {
			return masked.append(MASK).toString();
		}
		return masked.append(MASK.repeat(length - 2))
			.appendCodePoint(codePoints[length - 1])
			.toString();
	}

	/**
	 * 이메일에서 '@' 앞부분의 첫 글자만 남기고 나머지를 가린다. '@' 뒤(도메인)는 메일 발송 문제를 살필 때 필요해 그대로 둔다. 앞뒤
	 * 공백은 떼고 센다.
	 *
	 * <ul>
	 *   <li>앞부분이 한 글자: 전부 가린다. 예) a@example.com → *@example.com</li>
	 *   <li>앞부분이 두 글자 이상: 첫 글자만 남기고 나머지 글자 수만큼 가린다. 예) user@example.com → u***@example.com</li>
	 *   <li>'@' 가 없으면 전체를 앞부분으로 보고 같은 규칙으로 가린다. 예) user → u***</li>
	 * </ul>
	 *
	 * <p>'@' 는 마지막 것을 기준으로 나눈다. 따옴표로 감싼 앞부분에는 '@' 가 들어갈 수 있지만 도메인에는 들어갈 수 없다.
	 *
	 * @return 가린 이메일. null 이면 null, 공백뿐이면 빈 문자열
	 */
	public static String maskEmail(String email) {
		if (email == null) {
			return null;
		}
		String stripped = email.strip();
		int at = stripped.lastIndexOf('@');
		String localPart = at < 0 ? stripped : stripped.substring(0, at);
		String domainPart = at < 0 ? "" : stripped.substring(at);
		return maskAllButFirst(localPart) + domainPart;
	}

	private static String maskAllButFirst(String text) {
		int[] codePoints = text.codePoints().toArray();
		int length = codePoints.length;
		if (length == 0) {
			return "";
		}
		if (length == 1) {
			return MASK;
		}
		return new StringBuilder().appendCodePoint(codePoints[0])
			.append(MASK.repeat(length - 1))
			.toString();
	}
}
