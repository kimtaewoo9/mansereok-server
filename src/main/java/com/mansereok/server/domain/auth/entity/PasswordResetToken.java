package com.mansereok.server.domain.auth.entity;

import com.mansereok.server.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 제약 이름을 고정해 엔티티, schema.sql, 운영 DB 가 같은 이름을 쓰게 한다. 운영은 ddl-auto: validate 라 UNIQUE 를 검사하지
// 않으므로, 바꿀 때는 운영 DDL 과 schema.sql 을 함께 고친다.
@Entity
@Table(
	name = "password_reset_tokens",
	uniqueConstraints = {
		// 메일로 받은 토큰으로 한 행을 찾는다(findByToken). 같은 토큰이 두 행이면 조회가 실패한다.
		@UniqueConstraint(name = "uk_password_reset_tokens_token", columnNames = "token"),
		// 사용자 한 명에 재설정 토큰 하나(@OneToOne). 이름을 적지 않으면 Hibernate 가 UK 로 시작하는 해시 이름을 붙인다.
		@UniqueConstraint(name = "uk_password_reset_tokens_user_id", columnNames = "user_id")
	}
)
@Getter
@NoArgsConstructor
public class PasswordResetToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String token;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(nullable = false)
	private LocalDateTime expiryDate;

	public PasswordResetToken(User user) {
		this.user = user;
		this.token = UUID.randomUUID().toString();
		this.expiryDate = LocalDateTime.now().plusMinutes(15); // 15분 유효
	}

	public boolean isExpired() {
		return LocalDateTime.now().isAfter(expiryDate);
	}
}
