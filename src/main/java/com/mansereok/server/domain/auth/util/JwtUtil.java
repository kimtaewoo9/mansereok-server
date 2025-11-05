package com.mansereok.server.domain.auth.util;

import com.mansereok.server.global.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import javax.crypto.SecretKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtUtil {

	private final SecretKey secretKey;
	private final JwtProperties jwtProperties;

	/**
	 * Access Token을 생성한다 (짧은 만료 시간).
	 *
	 * @param username         사용자명
	 * @param additionalClaims 추가할 클레임 정보
	 * @return 생성된 Access Token
	 */
	public String generateAccessToken(String username, Map<String, Object> additionalClaims) {
		Instant now = Instant.now();
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
	 * AccessToken 을 생성한다 (추가 클레임 없음).
	 *
	 * @param username 사용자명
	 * @return 생성된 Access Token
	 */
	public String generateAccessToken(String username) {
		return generateAccessToken(username, null);
	}

	/**
	 * JWT 토큰의 유효성을 검증한다. 서명, 만료시간, 발급자 등을 종합적으로 검증
	 *
	 * @param token 검증할 JWT 토큰
	 * @return 토큰이 유효하면 true, 그렇지 않으면 false
	 */
	public boolean validateToken(String token) {
		try {
			Jwts.parser()
				.verifyWith(secretKey)
				.requireIssuer(jwtProperties.issuer())
				.build()
				.parseSignedClaims(token);
			return true;
		} catch (ExpiredJwtException e) {
			log.debug("JWT 토큰이 만료되었습니다: {}", e.getMessage());
			return false;
		} catch (UnsupportedJwtException e) {
			log.debug("지원하지 않는 JWT 토큰입니다: {}", e.getMessage());
			return false;
		} catch (MalformedJwtException e) {
			log.debug("JWT 토큰 형식이 올바르지 않습니다: {}", e.getMessage());
			return false;
		} catch (SignatureException e) {
			log.debug("JWT 토큰의 서명이 유효하지 않습니다: {}", e.getMessage());
			return false;
		} catch (IllegalArgumentException e) {
			log.debug("JWT 토큰이 비어있거나 올바르지 않습니다: {}", e.getMessage());
			return false;
		} catch (JwtException e) {
			log.debug("JWT 토큰 검증 실패: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * 토큰이 만료되었는지 확인한다.
	 *
	 * @param token JWT 토큰
	 * @return 만료되었으면 true, 그렇지 않으면 false
	 */
	public boolean isTokenExpired(String token) {
		try {
			Claims claims = extractAllClaims(token);
			return claims.getExpiration().before(new Date());
		} catch (JwtException e) {
			log.debug("토큰 만료 확인 중 오류 발생: {}", e.getMessage());
			return true;  // 파싱 실패 시 만료된 것으로 간주
		}
	}

	/**
	 * 토큰에서 사용자명을 추출한다.
	 *
	 * @param token JWT 토큰
	 * @return 사용자명 (subject)
	 */
	public String extractUsername(String token) {
		return extractAllClaims(token).getSubject();
	}

	/**
	 * 토큰에서 사용자 역할을 추출한다.
	 *
	 * @param token JWT 토큰
	 * @return 사용자 역할 문자열
	 */
	public String extractRole(String token) {
		Object role = extractClaim(token, "role");
		return role != null ? role.toString() : null;
	}

	/**
	 * 토큰에서 사용자 ID를 추출한다.
	 *
	 * @param token JWT 토큰
	 * @return 사용자 ID
	 */
	public Long extractUserId(String token) {
		Object userId = extractClaim(token, "userId");
		if (userId == null) {
			return null;
		}

		try {
			if (userId instanceof Number) {
				return ((Number) userId).longValue();
			} else {
				return Long.valueOf(userId.toString());
			}
		} catch (NumberFormatException e) {
			log.warn("사용자 ID 변환 실패: {}", userId);
			return null;
		}
	}

	/**
	 * 토큰에서 이메일을 추출한다.
	 *
	 * @param token JWT 토큰
	 * @return 이메일 주소
	 */
	public String extractEmail(String token) {
		Object email = extractClaim(token, "email");
		return email != null ? email.toString() : null;
	}

	/**
	 * 토큰에서 발급 시간을 추출한다.
	 *
	 * @param token JWT 토큰
	 * @return 발급 시간
	 */
	public Date extractIssuedAt(String token) {
		return extractAllClaims(token).getIssuedAt();
	}

	/**
	 * 토큰에서 만료 시간을 추출한다.
	 *
	 * @param token JWT 토큰
	 * @return 만료 시간
	 */
	public Date extractExpiration(String token) {
		return extractAllClaims(token).getExpiration();
	}

	/**
	 * 토큰에서 발급자를 추출한다.
	 *
	 * @param token JWT 토큰
	 * @return 발급자
	 */
	public String extractIssuer(String token) {
		return extractAllClaims(token).getIssuer();
	}

	/**
	 * 토큰에서 특정 클레임을 추출한다.
	 *
	 * @param token     JWT 토큰
	 * @param claimName 클레임 이름
	 * @return 클레임 값
	 */
	public Object extractClaim(String token, String claimName) {
		return extractAllClaims(token).get(claimName);
	}

	/**
	 * 토큰에서 모든 클레임을 추출한다.
	 *
	 * @param token JWT 토큰
	 * @return Claims 객체
	 * @throws JwtException 토큰이 유효하지 않은 경우
	 */
	public Claims extractAllClaims(String token) {
		return Jwts.parser()
			.verifyWith(secretKey)
			.build()
			.parseSignedClaims(token)
			.getPayload();
	}

	/**
	 * 토큰의 남은 유효 시간을 밀리초로 반환한다.
	 *
	 * @param token JWT 토큰
	 * @return 남은 유효 시간 (밀리초), 만료된 경우 0
	 */
	public long getTokenRemainingTime(String token) {
		try {
			Date expiration = extractExpiration(token);
			long remaining = expiration.getTime() - System.currentTimeMillis();
			return Math.max(0, remaining);
		} catch (JwtException e) {
			log.debug("토큰 남은 시간 계산 중 오류 발생: {}", e.getMessage());
			return 0;
		}
	}

	/**
	 * 토큰의 남은 유효 시간을 초 단위로 반환한다.
	 *
	 * @param token JWT 토큰
	 * @return 남은 유효 시간 (초), 만료된 경우 0
	 */
	public long getTokenRemainingTimeInSeconds(String token) {
		return getTokenRemainingTime(token) / 1000;
	}

	/**
	 * 토큰이 곧 만료될 예정인지 확인한다.
	 *
	 * @param token            JWT 토큰
	 * @param thresholdMinutes 임계값 (분)
	 * @return 임계값 이내에 만료되면 true
	 */
	public boolean isTokenExpiringWithin(String token, int thresholdMinutes) {
		long remainingTimeMs = getTokenRemainingTime(token);
		long thresholdMs = thresholdMinutes * 60 * 1000L;
		return remainingTimeMs > 0 && remainingTimeMs <= thresholdMs;
	}

	/**
	 * 토큰의 상세 정보를 포함한 요약을 반환한다. 디버깅이나 로깅 목적으로 사용
	 *
	 * @param token JWT 토큰
	 * @return 토큰 정보 요약 문자열
	 */
	public String getTokenSummary(String token) {
		try {
			Claims claims = extractAllClaims(token);
			return String.format(
				"JWT Token Summary - Subject: %s, Issuer: %s, IssuedAt: %s, Expiration: %s, Valid: %s",
				claims.getSubject(),
				claims.getIssuer(),
				claims.getIssuedAt(),
				claims.getExpiration(),
				validateToken(token)
			);
		} catch (JwtException e) {
			return String.format("Invalid JWT Token: %s", e.getMessage());
		}
	}
}
