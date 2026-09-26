package com.mansereok.server.domain.user.repository;

import com.mansereok.server.domain.user.entity.RefreshToken;
import com.mansereok.server.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

	Optional<RefreshToken> findByToken(String token);

	/**
	 * 재발급에 낸 토큰을 "썼음" 으로 표시한다. 아직 쓰지 않았고, 폐기되지 않았고, 만료 전(만료 시각 &gt; now)일 때만 used_at 을 now 로
	 * 바꾸고 1 을 돌려준다. 그 밖(없는 토큰, 이미 쓴 토큰, 폐기·만료된 토큰)은 0 이다.
	 *
	 * <p>확인과 표시를 UPDATE 한 문장으로 하므로 같은 토큰으로 두 요청이 겹쳐도 1 을 받는 쪽은 하나다. 뒤 요청은 앞 요청이 커밋할 때까지
	 * 이 행의 잠금을 기다렸다가, 커밋된 used_at 을 보고 0 을 받는다. token 의 UNIQUE 인덱스로 한 행만 찾으므로 그 행만 잠근다.
	 */
	@Modifying
	@Query("UPDATE RefreshToken rt SET rt.usedAt = :now "
		+ "WHERE rt.token = :token AND rt.usedAt IS NULL AND rt.revoked = false AND rt.expiresAt > :now")
	int markUsedIfUsable(@Param("token") String token, @Param("now") LocalDateTime now);

	/**
	 * 토큰과 그 회원을 한 번에 읽는다. 트랜잭션이 끝난 뒤 회원을 지연 로딩하지 않게 한다.
	 */
	@Query("SELECT rt FROM RefreshToken rt JOIN FETCH rt.user WHERE rt.token = :token")
	Optional<RefreshToken> findWithUserByToken(@Param("token") String token);

	/**
	 * 토큰 행을 잠그고(SELECT ... FOR UPDATE) 읽는다. 잠근 트랜잭션이 끝날 때까지 이 토큰의 로그아웃·재발급은 기다린다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT rt FROM RefreshToken rt WHERE rt.token = :token")
	Optional<RefreshToken> findByTokenForUpdate(@Param("token") String token);

	/**
	 * 토큰 하나를 폐기한다(로그아웃). 폐기한 행 수(없는 토큰이면 0)를 돌려준다. 읽은 엔티티를 고쳐 저장하지 않고 UPDATE 한 문장으로
	 * 하므로, 그사이 다른 요청이 행을 고쳤어도 행 수 검사에 걸려 실패하지 않는다.
	 */
	@Modifying
	@Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.token = :token")
	int revokeByToken(@Param("token") String token);

	/**
	 * 회원의 폐기되지 않은 토큰 중 만든 시각이 from 이상 to 이하인 토큰의 id 를 잠그지 않고 읽는다. 로그아웃한 토큰이 이미 새 토큰으로
	 * 바뀌었을 때, 그 새 토큰(쓴 시각부터 유예 시간 안에 만든 토큰)을 찾는 데 쓴다.
	 */
	@Query("SELECT rt.id FROM RefreshToken rt "
		+ "WHERE rt.user.id = :userId AND rt.createdAt >= :from AND rt.createdAt <= :to AND rt.revoked = false")
	List<Long> findUnrevokedIdsCreatedBetween(@Param("userId") Long userId, @Param("from") LocalDateTime from,
		@Param("to") LocalDateTime to);

	/**
	 * id 로 고른 토큰들을 폐기한다. 기본 키로 찾으므로 고른 행만 잠근다.
	 */
	@Modifying
	@Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.id IN :ids")
	int revokeByIds(@Param("ids") List<Long> ids);

	/**
	 * 회원의 토큰을 모두 지운다. 회원 탈퇴(UserService.deleteUser)만 쓴다. 로그인·재발급은 다른 기기의 토큰을 지우지 않는다.
	 */
	@Modifying
	@Query("DELETE FROM RefreshToken rt WHERE rt.user = :user")
	void deleteByUser(User user);

	@Modifying
	@Query("UPDATE RefreshToken rt SET rt.revoked = true WHERE rt.user = :user")
	void revokeAllUserTokens(User user);

	/**
	 * 만료 시각이 now 보다 이른 토큰을 limit 개까지 지우고 지운 행 수를 돌려준다. JPQL 에는 DELETE ... LIMIT 이 없어 SQL 로 쓴다.
	 */
	@Modifying
	@Query(value = "DELETE FROM refresh_tokens WHERE expires_at < :now LIMIT :limit", nativeQuery = true)
	int deleteExpiredBefore(@Param("now") LocalDateTime now, @Param("limit") int limit);

	/**
	 * 쓴 시각이 usedBefore 보다 이른 토큰을 limit 개까지 지우고 지운 행 수를 돌려준다.
	 */
	@Modifying
	@Query(value = "DELETE FROM refresh_tokens WHERE used_at < :usedBefore LIMIT :limit", nativeQuery = true)
	int deleteUsedBefore(@Param("usedBefore") LocalDateTime usedBefore, @Param("limit") int limit);
}
