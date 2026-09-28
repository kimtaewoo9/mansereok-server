package com.mansereok.server.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.web.server.Cookie.SameSite;

/**
 * 리프레시 토큰 쿠키 설정(app.auth.refresh-cookie). 쿠키는 RefreshTokenCookies 한 곳에서 만든다.
 *
 * <p>sameSite 는 쿠키의 SameSite 속성이다. 기본 Lax. Lax 쿠키는 API 와 같은 사이트(예: www.namedsaju.com 과 namedsaju.com)의
 * 프론트엔드에서 보낸 요청에만 실린다. localhost 나 vercel.app 미리보기처럼 다른 사이트의 프론트엔드가 개발 API 를 부르는 환경은
 * None 으로 둔다. None 은 Secure 쿠키에만 허용되는데, 리프레시 쿠키는 늘 Secure 로 만든다.
 *
 * <p>yml 에는 None, none, LAX 처럼 대소문자를 가리지 않고 적을 수 있고, 없는 값을 적으면 기동 때 바인딩이 실패한다. 속성을 아예 빼는
 * OMITTED 는 받지 않는다. 빼면 브라우저마다 기본값이 달라 브라우저에 따라 쿠키가 실리기도 하고 안 실리기도 한다.
 */
@ConfigurationProperties(prefix = "app.auth.refresh-cookie")
public record RefreshCookieProperties(@DefaultValue("lax") SameSite sameSite) {

	public RefreshCookieProperties {
		if (sameSite == null || sameSite == SameSite.OMITTED) {
			throw new IllegalArgumentException(
				"app.auth.refresh-cookie.same-site 는 Strict, Lax, None 중 하나여야 합니다: " + sameSite);
		}
	}
}
