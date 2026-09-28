package com.mansereok.server.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SetCookieHeaderTest {

	@Test
	@DisplayName("이름·값·속성으로 나누고, 속성 이름은 소문자로, 값 없는 속성은 빈 문자열로 담는다")
	void splitsNameValueAndAttributes() {
		// when
		SetCookieHeader cookie = SetCookieHeader.parse("REFRESH_TOKEN=abc; Path=/; Max-Age=604800; "
			+ "Expires=Sat, 03 Oct 2026 00:00:00 GMT; Secure; HttpOnly; SameSite=Lax");

		// then
		assertThat(cookie.name()).isEqualTo("REFRESH_TOKEN");
		assertThat(cookie.value()).isEqualTo("abc");
		assertThat(cookie.attributes()).containsExactlyInAnyOrderEntriesOf(Map.of(
			"path", "/", "max-age", "604800", "expires", "Sat, 03 Oct 2026 00:00:00 GMT",
			"secure", "", "httponly", "", "samesite", "Lax"));
		assertThat(cookie.attributesWithoutExpires()).doesNotContainKey("expires").hasSize(5);
	}

	@Test
	@DisplayName("값이 빈 지우는 쿠키도 이름과 빈 값으로 나눈다")
	void parsesEmptyValue() {
		// when
		SetCookieHeader cookie = SetCookieHeader.parse("REFRESH_TOKEN=; Path=/; Max-Age=0");

		// then
		assertThat(cookie.name()).isEqualTo("REFRESH_TOKEN");
		assertThat(cookie.value()).isEmpty();
		assertThat(cookie.attributes()).containsEntry("max-age", "0");
	}

	@Test
	@DisplayName("같은 이름의 쿠키가 둘이면 findOnly 는 실패한다")
	void findOnlyFailsOnDuplicateName() {
		// given
		List<String> headers = List.of("REFRESH_TOKEN=; Max-Age=0", "JSESSIONID=; Max-Age=0",
			"REFRESH_TOKEN=new; Max-Age=604800");

		// when & then
		assertThat(SetCookieHeader.findAll(headers, "REFRESH_TOKEN")).extracting(SetCookieHeader::value)
			.containsExactly("", "new");
		assertThatThrownBy(() -> SetCookieHeader.findOnly(headers, "REFRESH_TOKEN"))
			.isInstanceOf(AssertionError.class)
			.hasMessageStartingWith("REFRESH_TOKEN 쿠키가 딱 하나 있어야 하는데 2개입니다");
	}
}
