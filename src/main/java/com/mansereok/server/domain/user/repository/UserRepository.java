package com.mansereok.server.domain.user.repository;

import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByUsername(String username);

	/**
	 * 이메일로 계정을 찾는다. null 을 넘기면 빈 값이다.
	 *
	 * <p>파생 쿼리(findByEmail)는 null 인자를 "email IS NULL" 로 바꿔 이메일이 비어 있는 아무 계정이나 돌려준다. 그래서 JPQL
	 * 로 직접 적는다. "= null" 비교는 어떤 행과도 맞지 않는다.
	 */
	@Query("select u from User u where u.email = :email")
	Optional<User> findByEmail(@Param("email") String email);

	/**
	 * 소셜 계정은 제공자와 사용자 번호를 함께 봐야 구별된다. 카카오 회원번호와 오래된 X 계정 번호처럼 제공자가 달라도 번호가 같을
	 * 수 있다.
	 */
	Optional<User> findBySocialTypeAndSocialId(SocialType socialType, String socialId);

	boolean existsByEmail(String email);

	/**
	 * 사용자 행을 SELECT ... FOR UPDATE 로 읽어 트랜잭션이 끝날 때까지 잠근다. 같은 사용자의 비밀번호 재설정 요청을 한 줄로 세워,
	 * 먼저 온 요청이 토큰을 넣고 커밋한 뒤에 다음 요청이 그 토큰을 보게 한다.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select u from User u where u.id = :id")
	Optional<User> findByIdForUpdate(@Param("id") Long id);

}
