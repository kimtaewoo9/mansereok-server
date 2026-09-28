package com.mansereok.server.support.fixture;

import com.mansereok.server.domain.user.entity.RefreshToken;
import com.mansereok.server.domain.user.entity.User;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 테스트용 RefreshToken 을 만든다. 쓴 시각(used_at)과 폐기 여부는 운영 코드에서 UPDATE 문으로만 바뀌어 엔티티에 바꾸는 메서드가 없다.
 * 이미 쓴 토큰이나 폐기된 토큰을 만들려면 리플렉션이 필요한데, 그 우회를 이 클래스 한 곳에만 둔다.
 *
 * <p>테스트는 결과에 영향을 주는 값만 바꾸고 나머지는 기본값을 쓴다. 호출할 때마다 새 빌더를 돌려주므로 테스트끼리 값이 섞이지 않는다.
 */
public final class RefreshTokenFixture {

	private final User user;
	private Long id;
	private String token = "presented-token";
	private LocalDateTime createdAt = LocalDateTime.of(2026, 9, 1, 10, 0);
	private LocalDateTime expiresAt = LocalDateTime.of(2026, 10, 1, 10, 0);
	private LocalDateTime usedAt;
	private boolean revoked;

	private RefreshTokenFixture(User user) {
		this.user = user;
	}

	/** 아직 쓰지 않았고 폐기되지 않은 토큰. 만료 시각은 2026-10-01 10:00 이다. */
	public static RefreshTokenFixture unusedTokenOf(User user) {
		return new RefreshTokenFixture(user);
	}

	/** DB 에 넣은 행처럼 id 를 채운다. 기본은 비어 있다. */
	public RefreshTokenFixture id(Long id) {
		this.id = id;
		return this;
	}

	public RefreshTokenFixture token(String token) {
		this.token = token;
		return this;
	}

	/** 만든 시각을 바꾼다. 만료 시각은 따로 정하지 않으면 2026-10-01 10:00 그대로다. */
	public RefreshTokenFixture createdAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
		return this;
	}

	public RefreshTokenFixture expiresAt(LocalDateTime expiresAt) {
		this.expiresAt = expiresAt;
		return this;
	}

	public RefreshTokenFixture usedAt(LocalDateTime usedAt) {
		this.usedAt = usedAt;
		return this;
	}

	public RefreshTokenFixture revoked() {
		this.revoked = true;
		return this;
	}

	public RefreshToken build() {
		RefreshToken refreshToken = RefreshToken.issue(token, user, createdAt, Duration.between(createdAt, expiresAt));
		ReflectionTestUtils.setField(refreshToken, "id", id);
		ReflectionTestUtils.setField(refreshToken, "usedAt", usedAt);
		ReflectionTestUtils.setField(refreshToken, "revoked", revoked);
		return refreshToken;
	}
}
