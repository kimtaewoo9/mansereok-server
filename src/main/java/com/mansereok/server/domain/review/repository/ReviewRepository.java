package com.mansereok.server.domain.review.repository;

import com.mansereok.server.domain.review.entity.Review;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

	// 1. 상품별 리뷰 목록 조회 (최신순 정렬)
	// TODO 인덱스 ..
	@Query("SELECT r FROM Review r "
		+ "WHERE r.subCategoryId = :subCategoryId AND r.isDeleted = false "
		+ "ORDER BY r.createdAt DESC")
	List<Review> findReviewsBySubCategory(
		@Param("subCategoryId") Long subCategoryId
	);

	@Query("SELECT r FROM Review r "
		+ "WHERE r.isDeleted = false "
		+ "ORDER BY r.createdAt DESC")
	List<Review> findAllLatestReviews();

	// 2. 주문 ID로 리뷰 존재 여부 확인 (하나의 주문당 하나의 리뷰 정책 검증용)
	boolean existsByOrderId(Long orderId);
}
