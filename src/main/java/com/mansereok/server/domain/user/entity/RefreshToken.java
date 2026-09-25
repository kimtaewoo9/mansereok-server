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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 인덱스 이름을 고정해 엔티티, schema.sql, 운영 DB 가 같은 이름을 쓰게 한다. 운영은 ddl-auto: validate 라 인덱스를 검사하지
// 않으므로, 바꿀 때는 운영 DDL 과 schema.sql 을 함께 고친다.
@Table(
	name = "refresh_tokens",
	indexes = {
		// 만료된 토큰 삭제(deleteExpiredTokens)가 만료 시각으로 찾는다. 없으면 지울 때마다 표 전체를 훑고 잠근다.
		@Index(name = "idx_refresh_tokens_expires_at", columnList = "expires_at"),
		// 이미 새 토큰으로 바꾼(쓴) 토큰을 쓴 시각으로 골라 지울 때 쓴다.
		@Index(name = "idx_refresh_tokens_used_at", columnList = "used_at")
	}
)
@Entity
@Getter
@Setter
@NoArgsConstructor
public class RefreshToken {

	// 세션과의 차이
	// 1. 로그인/토큰 갱신때만 DB 조회
	// 2. 최소한의 정보만 저장 .
	// 3. 대부분의 API 요청은 여전히 stateless

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(unique = true, nullable = false, length = 500)
	private String token; //

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "used_at")
	private LocalDateTime usedAt;

	@Column(nullable = false)
	private boolean revoked = false; // 토큰 무효화 여부 .

	// 생성자
	public RefreshToken(String token, User user, LocalDateTime expiresAt) {
		this.token = token;
		this.user = user;
		this.expiresAt = expiresAt;
		this.createdAt = LocalDateTime.now();
	}

	/**
	 * Refresh Token이 만료되었는지 확인한다.
	 */
	public boolean isExpired() {
		return LocalDateTime.now().isAfter(expiresAt);
	}

	/**
	 * Refresh Token이 유효한지 확인한다. 만료되지 않았고 무효화되지 않은 경우에만 유효
	 */
	public boolean isValid() {
		return !revoked && !isExpired();
	}
}
