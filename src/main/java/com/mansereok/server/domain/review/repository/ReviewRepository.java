package com.mansereok.server.domain.review.repository;

import com.mansereok.server.domain.review.entity.Review;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

	// 주문 ID로 리뷰 존재 여부 확인 (하나의 주문당 하나의 리뷰 정책 검증용)
	boolean existsByOrderId(Long orderId);

	/*
	 * 아래 두 페이지 쿼리는 같은 모양이다. 안쪽 SELECT 가 인덱스만 읽어 한 페이지의 id 를 고르고, 바깥에서 그 id 로 행 전체를 읽는다.
	 *
	 * 정렬은 created_at DESC, id ASC 다. 인덱스의 created_at DESC 뒤에는 기본 키 id 가 오름차순으로 붙어 있어서, 이 순서는 인덱스를
	 * 읽는 순서 그대로라 따로 정렬하지 않는다(id DESC 로 바꾸면 filesort 가 생긴다). id 까지 넣어야 같은 시각에 쓴 리뷰도 순서가 하나로
	 * 정해져, 페이지 경계에서 같은 리뷰가 두 페이지에 나오거나 빠지지 않는다.
	 *
	 * 바깥 SELECT 에도 같은 ORDER BY 를 둔다. 안쪽의 정렬은 어떤 id 를 고를지만 정하고, JOIN 한 결과의 순서는 SQL 이 보장하지 않는다.
	 * 옵티마이저가 JOIN 순서를 바꾸면 최신순이 깨진다. 바깥 정렬은 고른 한 페이지(limit 건)만 다시 정렬하므로 비용이 거의 없다.
	 */

	// 상품별 리뷰 한 페이지. 안쪽은 idx_subcat_del_created 만 읽는다.
	@Query(
		value = "SELECT r.id, r.user_id, r.sub_category_id, r.order_id, " +
			"r.content, r.user_name, r.user_email, r.is_deleted, " +
			"r.created_at, r.updated_at " +
			"FROM (" +
			"   SELECT id " +
			"   FROM reviews " +
			"   WHERE sub_category_id = :subCategoryId AND is_deleted = false " +
			"   ORDER BY created_at DESC, id ASC " +
			"   LIMIT :limit OFFSET :offset " +
			") t " +
			"JOIN reviews r ON t.id = r.id " +
			"ORDER BY r.created_at DESC, r.id ASC",
		nativeQuery = true
	)
	List<Review> findReviewsBySubCategoryWithPagination(
		@Param("subCategoryId") Long subCategoryId,
		@Param("offset") long offset,
		@Param("limit") int limit
	);

	// 모든 상품의 리뷰 한 페이지. 안쪽은 idx_del_created 만 읽는다.
	@Query(
		value = "SELECT r.id, r.user_id, r.sub_category_id, r.order_id, " +
			"r.content, r.user_name, r.user_email, r.is_deleted, " +
			"r.created_at, r.updated_at " +
			"FROM (" +
			"   SELECT id " +
			"   FROM reviews " +
			"   WHERE is_deleted = false " +
			"   ORDER BY created_at DESC, id ASC " +
			"   LIMIT :limit OFFSET :offset " +
			") t " +
			"JOIN reviews r ON t.id = r.id " +
			"ORDER BY r.created_at DESC, r.id ASC",
		nativeQuery = true
	)
	List<Review> findAllReviewsWithPagination(
		@Param("offset") long offset,
		@Param("limit") int limit
	);

	@Query(
		value = "SELECT count(*) FROM reviews " +
			"WHERE sub_category_id = :subCategoryId AND is_deleted = false",
		nativeQuery = true
	)
	long countBySubCategory(@Param("subCategoryId") Long subCategoryId);

	@Query(
		value = "SELECT count(*) FROM reviews WHERE is_deleted = false",
		nativeQuery = true
	)
	long countAllReviews();

	@Modifying
	@Query(
		value = "DELETE "
			+ "FROM reviews "
			+ "WHERE user_id = :userId",
		nativeQuery = true
	)
	void deleteAllByUserId(@Param("userId") Long userId);
}
