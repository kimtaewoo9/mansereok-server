package com.mansereok.server.support.fixture;

import com.mansereok.server.domain.auth.util.JwtUtil;
import com.mansereok.server.global.config.JwtProperties;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Date;
import javax.crypto.SecretKey;

/**
 * JWT 필터와 인증 진입점 테스트가 함께 쓰는 액세스 토큰과 JwtUtil 을 만든다.
 *
 * <p>JwtUtil 은 운영과 같은 규칙(HMAC 서명, 발급자 {@link #ISSUER}, 만료)으로 검증하고, 지금 시각은 {@link #NOW} 에 고정한다.
 * 토큰은 JwtUtil 의 발급 메서드가 아니라 {@code Jwts.builder()} 로 직접 만든다. 테스트가 어떤 클레임을 빼거나 바꿨는지, 어느 키로
 * 서명했는지가 호출하는 쪽에 그대로 보이게 하려는 것이다.
 */
public final class AccessTokenFixture {

	/** 검증하는 쪽의 지금 시각. */
	public static final Instant NOW = Instant.parse("2026-01-15T00:00:00Z");
	/** 이 서버가 토큰에 넣고 검증할 때 요구하는 발급자. */
	public static final String ISSUER = "www.namedsaju.com";
	/** {@link #claimsOfThisServer()} 가 userId 클레임에 넣는 회원 id. 필터가 (userId, subject) 로 계정이 지금도 있는지 확인한다. */
	public static final long MEMBER_ID = 1L;

	private static final String SECRET = "access-token-fixture-secret-0123456789-abcdef";
	private static final String OTHER_SECRET = "another-server-secret-0123456789-abcdefghij";
	private static final Duration ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(30);

	private AccessTokenFixture() {
	}

	/**
	 * 운영과 같은 규칙으로 검증하고, 지금 시각이 {@link #NOW} 인 JwtUtil.
	 */
	public static JwtUtil jwtUtil() {
		JwtProperties jwtProperties = new JwtProperties(SECRET, ACCESS_TOKEN_LIFETIME.toMillis(), 604_800_000L,
			ISSUER);
		return new JwtUtil(key(SECRET), jwtProperties, Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
	}

	/**
	 * 필터가 읽는 클레임(subject member, role ROLE_USER, userId {@link #MEMBER_ID})과 검증 조건(발급자, 발급 시각 NOW, 만료
	 * NOW+30분)을 이 서버와 같게 채운 빌더. 이 서버가 실제로 발급하는 토큰에 더 들어 있는 email(이메일 로그인 토큰은 name 도)은
	 * 필터가 읽지 않아 넣지 않는다. 서명하기 전에 바꾸고 싶은 클레임만 덮어쓴다. 값에 null 을 넣으면 그 클레임이 빠진다.
	 */
	public static JwtBuilder claimsOfThisServer() {
		return Jwts.builder()
			.subject("member")
			.issuer(ISSUER)
			.issuedAt(Date.from(NOW))
			.expiration(Date.from(NOW.plus(ACCESS_TOKEN_LIFETIME)))
			.claim("role", "ROLE_USER")
			.claim("userId", MEMBER_ID);
	}

	/**
	 * 이 서버의 비밀키로 서명한다.
	 */
	public static String signedByThisServer(JwtBuilder claims) {
		return claims.signWith(key(SECRET)).compact();
	}

	/**
	 * 이 서버와 다른 비밀키로 서명한다.
	 */
	public static String signedWithOtherKey(JwtBuilder claims) {
		return claims.signWith(key(OTHER_SECRET)).compact();
	}

	private static SecretKey key(String secret) {
		return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
	}
}
