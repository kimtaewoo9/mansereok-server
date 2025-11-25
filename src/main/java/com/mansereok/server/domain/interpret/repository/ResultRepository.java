package com.mansereok.server.domain.interpret.repository;

import com.mansereok.server.domain.interpret.entity.Result;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ResultRepository extends JpaRepository<Result, Long> {

	List<Result> findAllByUserIdOrderByCreatedAtDesc(Long userId);

	Optional<Result> findByPaymentId(Long paymentId);

	@Modifying(clearAutomatically = true)
	@Query("UPDATE Result r SET r.ogImageUrl = :ogImageUrl WHERE r.id = :id")
	void updateOgImageUrl(@Param("id") Long id, @Param("ogImageUrl") String ogImageUrl);

	void deleteAllByUserId(Long userId);
}
