package com.mansereok.server.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 인덱스 이름을 고정해 엔티티, schema.sql, 운영 DB 가 같은 이름을 쓰게 한다. 운영은 ddl-auto: validate 라 인덱스를 검사하지
// 않으므로, 바꿀 때는 운영 DDL 과 schema.sql 을 함께 고친다.
@Table(
	name = "refresh_tokens",
	indexes = {
		// 정리 작업(RefreshTokenCleanupScheduler)이 만료 시각이 지난 토큰을 찾을 때 쓴다. 없으면 지울 때마다 표 전체를 훑고 잠근다.
		@Index(name = "idx_refresh_tokens_expires_at", columnList = "expires_at"),
		// 정리 작업이 새 토큰으로 바꾼(쓴) 지 오래된 토큰을 쓴 시각으로 찾을 때 쓴다.
		@Index(name = "idx_refresh_tokens_used_at", columnList = "used_at")
	}
)
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefreshToken {

	// 세션과의 차이
	// 1. 로그인/토큰 갱신때만 DB 조회
	// 2. 최소한의 정보만 저장 .
	// 3. 대부분의 API 요청은 여전히 stateless

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(unique = true, nullable = false, length = 500)
	private String token;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	// 이 토큰으로 새 토큰을 받은(쓴) 시각. 재발급의 조건부 UPDATE(RefreshTokenRepository.markUsedIfUsable)만 채운다.
	@Column(name = "used_at")
	private LocalDateTime usedAt;

	// 로그아웃·비밀번호 재설정으로 폐기됐는지. RefreshTokenRepository 의 UPDATE 문만 바꾼다.
	@Column(nullable = false)
	private boolean revoked = false;

	/**
	 * 회원에게 줄 새 토큰을 만든다. now 부터 lifetime 동안 쓸 수 있다.
	 *
	 * @param now 지금 시각. 호출하는 쪽이 주입받은 Clock 으로 구한다.
	 */
	public static RefreshToken issue(String token, User user, LocalDateTime now, Duration lifetime) {
		RefreshToken refreshToken = new RefreshToken();
		refreshToken.token = token;
		refreshToken.user = user;
		refreshToken.createdAt = now;
		refreshToken.expiresAt = now.plus(lifetime);
		return refreshToken;
	}

	/**
	 * now 가 만료 시각과 같거나 지났으면 만료로 본다. 재발급의 조건부 UPDATE 도 "만료 시각 &gt; now" 인 행만 고치므로 두 판단의 경계가
	 * 같다.
	 */
	public boolean isExpiredAt(LocalDateTime now) {
		return !now.isBefore(expiresAt);
	}

	/**
	 * 이미 새 토큰으로 바꾼 토큰이고, 바꾼 시각부터 now 까지 grace 를 넘지 않았는지. 경계(정확히 grace 가 지난 순간)는 넘지 않은 쪽이다.
	 */
	public boolean wasUsedWithin(Duration grace, LocalDateTime now) {
		return usedAt != null && !now.isAfter(usedAt.plus(grace));
	}
}
