package com.mansereok.server.domain.user.repository;

import com.mansereok.server.domain.user.entity.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

	Optional<User> findByUsername(String username);

	Optional<User> findByEmail(String email);
	
	Optional<User> findBySocialId(String socialId);

	boolean existsByEmail(String email);

}
