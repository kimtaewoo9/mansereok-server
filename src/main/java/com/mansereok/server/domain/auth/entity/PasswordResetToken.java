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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
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
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordResetToken {

	// 재설정 링크를 쓸 수 있는 시간
	private static final Duration VALID_FOR = Duration.ofMinutes(15);

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

	/**
	 * 사용자의 첫 재설정 토큰을 만든다. now 부터 15분 동안 쓸 수 있다.
	 *
	 * @param now 지금 시각. 호출하는 쪽이 주입받은 Clock 으로 구한다.
	 */
	public PasswordResetToken(User user, LocalDateTime now) {
		this.user = user;
		this.token = newTokenValue();
		this.expiryDate = now.plus(VALID_FOR);
	}

	/**
	 * 토큰이 만료된 사용자가 다시 요청하면 행을 지우고 새로 넣는 대신, 이 행의 토큰 값을 새로 만들고 만료 시각을 now 부터 15분 뒤로
	 * 바꾼다. 앞서 보낸 링크의 토큰 값은 더 이상 어느 행에도 없으므로 쓸 수 없다. 만료 전의 재요청은 이 메서드를 부르지 않고 같은 토큰을
	 * 다시 보낸다(UserService.requestPasswordReset).
	 *
	 * @param now 지금 시각. 호출하는 쪽이 주입받은 Clock 으로 구한다.
	 */
	public void reissue(LocalDateTime now) {
		this.token = newTokenValue();
		this.expiryDate = now.plus(VALID_FOR);
	}

	/**
	 * now 가 만료 시각과 같거나 지났으면 만료로 본다. 토큰을 쓸 때의 조건부 DELETE(PasswordResetTokenRepository.consume)도
	 * "만료 시각 &gt; now" 인 행만 지우므로, 두 판단의 경계가 같다.
	 */
	public boolean isExpiredAt(LocalDateTime now) {
		return !now.isBefore(expiryDate);
	}

	private static String newTokenValue() {
		return UUID.randomUUID().toString();
	}
}
