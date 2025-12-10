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

	@Query(
		value = "SELECT r.id, r.user_id, r.sub_category_id, r.order_id, " +
			"r.content, r.user_name, r.user_email, r.is_deleted, " +
			"r.created_at, r.updated_at " +
			"FROM (" +
			"   SELECT id " +
			"   FROM reviews " +
			"   WHERE sub_category_id = :subCategoryId AND is_deleted = false " +
			"   ORDER BY created_at DESC " +
			"   LIMIT :limit OFFSET :offset " +
			") t " +
			"JOIN reviews r ON t.id = r.id",
		nativeQuery = true
	)
	List<Review> findReviewsBySubCategoryWithPagination(
		@Param("subCategoryId") Long subCategoryId,
		@Param("offset") long offset,
		@Param("limit") int limit
	);

	// [NEW] 2. 전체 리뷰 페이징 (조건절 없이 전체 대상)
	@Query(
		value = "SELECT r.id, r.user_id, r.sub_category_id, r.order_id, " +
			"r.content, r.user_name, r.user_email, r.is_deleted, " +
			"r.created_at, r.updated_at " +
			"FROM (" +
			"   SELECT id " +
			"   FROM reviews " +
			"   WHERE is_deleted = false " + // 삭제 안 된 것만
			"   ORDER BY created_at DESC " + // 인덱스 활용 (idx_del_created)
			"   LIMIT :limit OFFSET :offset " +
			") t " +
			"JOIN reviews r ON t.id = r.id",
		nativeQuery = true
	)
	List<Review> findAllReviewsWithPagination(
		@Param("offset") long offset,
		@Param("limit") int limit
	);

	// [NEW] 전체 개수 카운트 (전체 조회용)
	@Query(
		value = "SELECT count(*) FROM reviews WHERE is_deleted = false",
		nativeQuery = true
	)
	long countAllReviews();
}
