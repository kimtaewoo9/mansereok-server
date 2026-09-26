package com.mansereok.server.domain.auth.util;

import com.mansereok.server.global.config.JwtProperties;
import com.mansereok.server.global.config.RefreshCookieProperties;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * 리프레시 토큰 쿠키(REFRESH_TOKEN)를 만드는 곳은 여기 하나다. 이메일 로그인·소셜 로그인·재발급은 {@link #issue(String)} 를,
 * 재발급 실패·로그아웃·탈퇴는 {@link #expire()} 를 쓴다. 컨트롤러는 돌려받은 쿠키를 Set-Cookie 헤더로 싣기만 한다.
 *
 * <p>두 쿠키는 Path=/, HttpOnly, Secure, SameSite(app.auth.refresh-cookie.same-site) 가 늘 같다. 브라우저는 이름·Path·도메인이
 * 같아야 같은 쿠키로 보고 지우므로, 지우는 쿠키도 만든 쿠키와 속성을 맞춘다.
 *
 * <p>쿠키 수명(Max-Age)은 DB 에 적는 토큰 만료와 같은 app.jwt.refresh-token-expiration 에서 읽는다. 둘을 따로 적으면 쿠키가 먼저
 * 사라져 아직 유효한 토큰으로도 재발급하지 못하거나, 설정을 바꿔도 쿠키 수명은 그대로 남는다.
 */
@Component
@RequiredArgsConstructor
public class RefreshTokenCookies {

	public static final String NAME = "REFRESH_TOKEN";

	private final JwtProperties jwtProperties;
	private final RefreshCookieProperties refreshCookieProperties;

	/**
	 * 새 리프레시 토큰을 담은 쿠키. 토큰 수명만큼 브라우저에 남는다.
	 */
	public ResponseCookie issue(String token) {
		return cookie(token, Duration.ofMillis(jwtProperties.refreshTokenExpiration()));
	}

	/**
	 * 브라우저의 리프레시 토큰 쿠키를 지우는 쿠키(빈 값, Max-Age=0).
	 */
	public ResponseCookie expire() {
		return cookie("", Duration.ZERO);
	}

	private ResponseCookie cookie(String value, Duration maxAge) {
		return ResponseCookie.from(NAME, value)
			.path("/")
			.httpOnly(true)
			.secure(true)
			.sameSite(refreshCookieProperties.sameSite().attributeValue())
			.maxAge(maxAge)
			.build();
	}
}
