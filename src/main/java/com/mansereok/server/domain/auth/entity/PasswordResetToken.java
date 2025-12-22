package com.mansereok.server.domain.auth.entity;

import com.mansereok.server.domain.user.entity.User;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "password_reset_tokens")
@Getter
@NoArgsConstructor
public class PasswordResetToken {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String token;

	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id")
	private User user;

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
