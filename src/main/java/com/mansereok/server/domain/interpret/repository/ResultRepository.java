package com.mansereok.server.domain.interpret.repository;

import com.mansereok.server.domain.interpret.entity.Result;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ResultRepository extends JpaRepository<Result, Long> {

	List<Result> findAllByUserIdOrderByCreatedAtDesc(Long userId);

	Optional<Result> findByPaymentId(Long paymentId);
}
