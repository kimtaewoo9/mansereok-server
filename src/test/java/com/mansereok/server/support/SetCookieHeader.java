package com.mansereok.server.support;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Set-Cookie 헤더 한 줄을 쿠키 이름, 값, 속성으로 나눈다. 쿠키를 내려주는 여러 API 의 쿠키 속성이 같은지 비교하는 테스트가 쓴다.
 *
 * <p>속성 이름은 소문자로 바꿔 담는다. 브라우저가 속성 이름의 대소문자를 가리지 않고, ResponseCookie 와 MockHttpServletResponse 가
 * 적는 모양도 조금씩 달라서다. HttpOnly·Secure 처럼 값이 없는 속성은 빈 문자열을 값으로 담는다. Expires 는 응답을 만든 시각에 따라
 * 바뀌므로 비교할 때는 {@link #attributesWithoutExpires()} 를 쓴다.
 *
 * @param name       쿠키 이름
 * @param value      쿠키 값. 지우는 쿠키는 빈 문자열이다
 * @param attributes 소문자 속성 이름과 그 값
 */
public record SetCookieHeader(String name, String value, Map<String, String> attributes) {

	public SetCookieHeader {
		attributes = Map.copyOf(attributes);
	}

	/**
	 * "이름=값; 속성=값; 속성" 모양의 Set-Cookie 헤더 값 하나를 나눈다.
	 */
	public static SetCookieHeader parse(String header) {
		String[] parts = header.split(";");
		String[] nameAndValue = parts[0].split("=", 2);
		if (nameAndValue.length != 2) {
			throw new IllegalArgumentException("이름=값 으로 시작하지 않는 Set-Cookie 헤더입니다: " + header);
		}
		Map<String, String> attributes = new LinkedHashMap<>();
		for (int i = 1; i < parts.length; i++) {
			String[] attribute = parts[i].trim().split("=", 2);
			attributes.put(attribute[0].toLowerCase(Locale.ROOT), attribute.length == 2 ? attribute[1] : "");
		}
		return new SetCookieHeader(nameAndValue[0].trim(), nameAndValue[1].trim(), attributes);
	}

	/**
	 * 응답에 실린 Set-Cookie 헤더들 가운데 이름이 name 인 쿠키를 모두 찾아 헤더 순서대로 돌려준다.
	 */
	public static List<SetCookieHeader> findAll(Collection<String> headers, String name) {
		return headers.stream()
			.map(SetCookieHeader::parse)
			.filter(cookie -> cookie.name().equals(name))
			.toList();
	}

	/**
	 * 응답에 이름이 name 인 쿠키가 딱 하나 있으면 그 쿠키를 돌려준다. 없거나 둘 이상이면 실패한다. 같은 이름의 Set-Cookie 가 한 응답에
	 * 두 번 나가면 브라우저가 어느 쪽을 남길지 기대기 어려우므로 그것도 잘못으로 본다.
	 */
	public static SetCookieHeader findOnly(Collection<String> headers, String name) {
		List<SetCookieHeader> found = findAll(headers, name);
		if (found.size() != 1) {
			throw new AssertionError(name + " 쿠키가 딱 하나 있어야 하는데 " + found.size() + "개입니다: " + headers);
		}
		return found.get(0);
	}

	/**
	 * Expires 를 뺀 속성. Max-Age 가 같으면 Expires 는 응답 시각만 다르므로 경로끼리 비교할 때 뺀다.
	 */
	public Map<String, String> attributesWithoutExpires() {
		Map<String, String> copy = new LinkedHashMap<>(attributes);
		copy.remove("expires");
		return copy;
	}
}
