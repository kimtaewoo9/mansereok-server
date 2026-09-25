package com.mansereok.server.domain.auth;

import com.mansereok.server.domain.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
	Optional<PasswordResetToken> findByToken(String token);

	/**
	 * 비밀번호 재설정을 다시 요청할 때 기존 토큰을 지운다(requestPasswordReset). 파생 삭제라 토큰을 조회한 뒤 한 건씩 지운다. 회원
	 * 탈퇴는 {@link #deleteAllByUserId} 를 쓴다.
	 */
	void deleteByUserId(Long userId);

	/**
	 * 사용자의 재설정 토큰을 DELETE 한 번으로 지운다. 회원 탈퇴가 users 행을 지우기 전에 부른다.
	 *
	 * <p>password_reset_tokens.user_id 는 users 를 외래 키로 가리킨다. 재설정을 요청하고 링크를 쓰지 않은 회원은 토큰 행이 남아
	 * 있어서, 이 행을 먼저 지우지 않으면 users DELETE 가 외래 키에 막혀 탈퇴 전체가 롤백된다.
	 */
	@Modifying
	@Query("DELETE FROM PasswordResetToken t WHERE t.user.id = :userId")
	void deleteAllByUserId(@Param("userId") Long userId);
}
