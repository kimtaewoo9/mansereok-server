package com.mansereok.server.domain.auth.util;

import com.mansereok.server.global.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import java.time.Clock;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Component;

/**
 * 액세스 토큰을 발급하고, 요청에 실려 온 토큰을 검증한다.
 *
 * <p>검증 규칙(서명 키, 발급자, 만료 판정에 쓰는 시계)은 생성할 때 한 번 정해 파서 하나에 담는다. JwtParser 는 불변이라
 * 요청마다 새로 만들지 않고 모든 스레드가 같은 파서를 쓴다. 발급 시각과 만료 판정은 모두 주입받은 Clock 을 따른다.
 */
@Component
public class JwtUtil {

	private final SecretKey secretKey;
	private final JwtProperties jwtProperties;
	private final Clock clock;
	private final JwtParser parser;

	public JwtUtil(SecretKey secretKey, JwtProperties jwtProperties, Clock clock) {
		this.secretKey = secretKey;
		this.jwtProperties = jwtProperties;
		this.clock = clock;
		this.parser = Jwts.parser()
			.verifyWith(secretKey)
			.requireIssuer(jwtProperties.issuer())
			.clock(() -> Date.from(clock.instant()))
			.build();
	}

	/**
	 * Access Token을 생성한다 (짧은 만료 시간).
	 *
	 * @param username         사용자명
	 * @param additionalClaims 추가할 클레임 정보
	 * @return 생성된 Access Token
	 */
	public String generateAccessToken(String username, Map<String, Object> additionalClaims) {
		Instant now = clock.instant();
		Instant expiration = now.plusMillis(jwtProperties.accessTokenExpiration());

		var builder = Jwts.builder()
			.subject(username)
			.issuer(jwtProperties.issuer())
			.issuedAt(Date.from(now))
			.expiration(Date.from(expiration));

		if (additionalClaims != null) {
			additionalClaims.forEach(builder::claim);
		}

		return builder.signWith(secretKey).compact();
	}

	/**
	 * 서명, 발급자, 만료를 모두 확인한 뒤 토큰의 클레임을 돌려준다.
	 *
	 * @param token JWT 토큰
	 * @return 검증을 통과한 토큰의 클레임
	 * @throws JwtException 서명이 틀리거나(SignatureException), 발급자가 다르거나(IncorrectClaimException,
	 *                      MissingClaimException), 만료됐거나(ExpiredJwtException), 형식이 틀린 경우
	 */
	public Claims parseVerifiedClaims(String token) {
		return parser.parseSignedClaims(token).getPayload();
	}
}
