package com.mansereok.server.domain.auth;

import com.mansereok.server.domain.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
	Optional<PasswordResetToken> findByToken(String token);
	void deleteByUserId(Long userId); // 기존 토큰 삭제용
}
