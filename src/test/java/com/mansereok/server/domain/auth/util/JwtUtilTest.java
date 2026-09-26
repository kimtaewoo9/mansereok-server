package com.mansereok.server.domain.auth.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.global.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MissingClaimException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 토큰 검증이 서명, 발급자, 만료를 모두 확인하는지 검증한다.
 *
 * <p>발급 시각과 만료 판정은 주입한 Clock 을 따르므로, 발급할 때와 검증할 때의 시계를 따로 고정해 시간이 흐른 상황을 만든다.
 * 발급 시각을 실제 오늘보다 과거로 두어, 검증이 주입한 시계 대신 시스템 시계를 읽으면 정상 토큰도 만료로 떨어져 테스트가 실패한다.
 */
class JwtUtilTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	private static final String SECRET = "jwt-util-test-secret-0123456789-abcdef";
	private static final String OTHER_SECRET = "jwt-util-test-other-secret-0123456789-xyz";
	private static final String ISSUER = "www.namedsaju.com";
	private static final long ACCESS_TOKEN_EXPIRATION_MILLIS = 1_800_000L;

	// 2026-01-15 09:00 (서울). JWT 는 시각을 초 단위로 저장하므로 초 경계에 둔다. 액세스 토큰은 30분 뒤인 09:30 에 만료된다.
	private static final Clock AT_ISSUE = Clock.fixed(Instant.parse("2026-01-15T00:00:00Z"), SEOUL);
	private static final Clock ONE_SECOND_BEFORE_EXPIRY = Clock.fixed(Instant.parse("2026-01-15T00:29:59Z"), SEOUL);
	private static final Clock ONE_SECOND_AFTER_EXPIRY = Clock.fixed(Instant.parse("2026-01-15T00:30:01Z"), SEOUL);

	private final JwtUtil jwtUtil = jwtUtil(SECRET, ISSUER, AT_ISSUE);

	@Nested
	@DisplayName("이 서버가 발급한 토큰이면")
	class WhenIssuedByThisServer {

		@Test
		@DisplayName("발급 때 넣은 사용자·역할과 Clock 기준 발급·만료 시각을 돌려준다")
		void returnsClaimsWithClockBasedTimes() {
			// given
			String token = jwtUtil.generateAccessToken("member", Map.of("role", "ROLE_USER"));

			// when
			Claims claims = jwtUtil.parseVerifiedClaims(token);

			// then
			assertThat(claims.getSubject()).isEqualTo("member");
			assertThat(claims.get("role", String.class)).isEqualTo("ROLE_USER");
			assertThat(claims.getIssuer()).isEqualTo(ISSUER);
			assertThat(claims.getIssuedAt()).isEqualTo(Date.from(Instant.parse("2026-01-15T00:00:00Z")));
			assertThat(claims.getExpiration()).isEqualTo(Date.from(Instant.parse("2026-01-15T00:30:00Z")));
		}

		@Test
		@DisplayName("시계가 만료 1초 전이면 아직 통과한다")
		void passesOneSecondBeforeExpiry() {
			// given
			String token = jwtUtil.generateAccessToken("member", Map.of("role", "ROLE_USER"));
			JwtUtil verifierOneSecondBeforeExpiry = jwtUtil(SECRET, ISSUER, ONE_SECOND_BEFORE_EXPIRY);

			// when
			Claims claims = verifierOneSecondBeforeExpiry.parseVerifiedClaims(token);

			// then
			assertThat(claims.getSubject()).isEqualTo("member");
		}

		@Test
		@DisplayName("시계가 만료 1초 뒤면 ExpiredJwtException 으로 거부한다")
		void rejectsOneSecondAfterExpiry() {
			// given
			String token = jwtUtil.generateAccessToken("member", Map.of("role", "ROLE_USER"));
			JwtUtil verifierOneSecondAfterExpiry = jwtUtil(SECRET, ISSUER, ONE_SECOND_AFTER_EXPIRY);

			// when & then
			assertThatThrownBy(() -> verifierOneSecondAfterExpiry.parseVerifiedClaims(token))
				.isInstanceOf(ExpiredJwtException.class);
		}
	}

	@Nested
	@DisplayName("같은 키로 서명했지만 발급자가 다르면")
	class WhenIssuerDiffers {

		@Test
		@DisplayName("발급자(iss)가 다르다는 IncorrectClaimException 으로 거부한다")
		void rejectsOtherIssuer() {
			// given
			String tokenFromOtherIssuer = jwtUtil(SECRET, "staging.namedsaju.com", AT_ISSUE)
				.generateAccessToken("member", Map.of("role", "ROLE_USER"));

			// when & then
			assertThatThrownBy(() -> jwtUtil.parseVerifiedClaims(tokenFromOtherIssuer))
				.isInstanceOfSatisfying(IncorrectClaimException.class, e -> {
					assertThat(e.getClaimName()).isEqualTo("iss");
					assertThat(e.getClaimValue()).isEqualTo(ISSUER);
				});
		}

		@Test
		@DisplayName("발급자가 아예 없어도 MissingClaimException 으로 거부한다")
		void rejectsMissingIssuer() {
			// given
			String tokenWithoutIssuer = Jwts.builder()
				.subject("member")
				.claim("role", "ROLE_USER")
				.issuedAt(Date.from(Instant.parse("2026-01-15T00:00:00Z")))
				.expiration(Date.from(Instant.parse("2026-01-15T00:30:00Z")))
				.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
				.compact();

			// when & then
			assertThatThrownBy(() -> jwtUtil.parseVerifiedClaims(tokenWithoutIssuer))
				.isInstanceOfSatisfying(MissingClaimException.class,
					e -> assertThat(e.getClaimName()).isEqualTo("iss"));
		}
	}

	@Nested
	@DisplayName("다른 키로 서명한 토큰이면")
	class WhenSignedWithOtherKey {

		@Test
		@DisplayName("SignatureException 으로 거부한다")
		void rejectsOtherKey() {
			// given
			String tokenSignedWithOtherKey = jwtUtil(OTHER_SECRET, ISSUER, AT_ISSUE)
				.generateAccessToken("member", Map.of("role", "ROLE_USER"));

			// when & then
			assertThatThrownBy(() -> jwtUtil.parseVerifiedClaims(tokenSignedWithOtherKey))
				.isInstanceOf(SignatureException.class);
		}
	}

	/**
	 * 운영 설정(JwtConfig)과 같은 방식으로 비밀키 문자열에서 서명 키를 만들고, 그 키와 발급자, 시계로 JwtUtil 을 만든다.
	 */
	private static JwtUtil jwtUtil(String secret, String issuer, Clock clock) {
		JwtProperties jwtProperties = new JwtProperties(secret, ACCESS_TOKEN_EXPIRATION_MILLIS, 604_800_000L, issuer);
		return new JwtUtil(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)), jwtProperties, clock);
	}
}
