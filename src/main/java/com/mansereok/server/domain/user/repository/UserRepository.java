package com.mansereok.server.domain.user.repository;

import com.mansereok.server.domain.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

	// 로그인 시 사용자 조회용
	Optional<User> findByUsername(String username);

	Optional<User> findByEmail(String email);

	// 회원가입 되어있는지 확인.
	Optional<User> findBySocialId(String socialId);

	// 회원가입 시 중복 체크용
	boolean existsByUsername(String username);

	boolean existsByEmail(String email);

}
