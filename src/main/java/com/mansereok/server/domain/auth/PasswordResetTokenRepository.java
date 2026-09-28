package com.mansereok.server.domain.auth;

import com.mansereok.server.domain.auth.entity.PasswordResetToken;
import java.time.LocalDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
	Optional<PasswordResetToken> findByToken(String token);

	/**
	 * 사용자의 재설정 토큰을 찾는다. 사용자당 한 행이다(uk_password_reset_tokens_user_id). 재설정을 다시 요청하면 이 행이 없을 때만
	 * 새로 넣고, 있으면 만료됐을 때만 값을 바꾼다. 만료 전이면 같은 토큰을 다시 보낸다.
	 */
	Optional<PasswordResetToken> findByUserId(Long userId);

	/**
	 * 재설정 토큰을 쓴다. 토큰 값이 아직 있고 만료 전(만료 시각 &gt; now)일 때만 그 행을 지우고 1 을 돌려준다. 이미 누가 썼거나,
	 * 만료된 뒤 재요청이 값을 바꿨거나, 만료됐으면 지울 행이 없어 0 이다.
	 *
	 * <p>확인과 삭제를 DELETE 한 문장으로 하므로 같은 토큰으로 두 요청이 겹쳐도 1 을 받는 쪽은 하나다. 뒤 요청은 앞 요청이 커밋할
	 * 때까지 잠금을 기다렸다가 지워진 행을 보고 0 을 받는다.
	 */
	@Modifying
	@Query("DELETE FROM PasswordResetToken t WHERE t.token = :token AND t.expiryDate > :now")
	int consume(@Param("token") String token, @Param("now") LocalDateTime now);

	/**
	 * 사용자의 재설정 토큰을 DELETE 한 번으로 지운다. 회원 탈퇴가 users 행을 잠근 뒤, 지우기 전에 부른다.
	 *
	 * <p>password_reset_tokens.user_id 는 users 를 외래 키로 가리킨다. 재설정을 요청하고 링크를 쓰지 않은 회원은 토큰 행이 남아
	 * 있어서, 이 행을 먼저 지우지 않으면 users DELETE 가 외래 키에 막혀 탈퇴 전체가 롤백된다.
	 */
	@Modifying
	@Query("DELETE FROM PasswordResetToken t WHERE t.user.id = :userId")
	void deleteAllByUserId(@Param("userId") Long userId);
}
