package com.mansereok.server.domain.review.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.support.LocalMySqlTest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.data.jpa.repository.Query;

/**
 * 리뷰 조회·탈퇴 삭제·상품 목록 쿼리가 실제 MySQL 에서 의도한 인덱스를 타는지 EXPLAIN 으로 확인한다.
 *
 * <p>테스트 DB 는 ddl-auto: update 라 엔티티 @Table 선언대로 인덱스가 생긴다. 운영은 validate 라 같은 이름의 DDL 을 손으로 적용한다.
 * 그래서 여기서 확인한 인덱스 이름과 실행 계획이, 운영에 적용한 뒤 운영에서 EXPLAIN 으로 견줄 기준이 된다.
 *
 * <p>ReviewRepository 의 네이티브 쿼리는 @Query 에 적힌 SQL 을 그대로 꺼내 EXPLAIN 한다. 쿼리를 고쳐 인덱스를 못 타게 되면 이
 * 테스트가 실패한다. 이름 붙은 인자(:subCategoryId 등)는 숫자 값으로 바꿔 넣는다. MySQL 드라이버도 기본 설정에서는 값을 SQL 에
 * 넣어 보내므로 실행 계획이 같다.
 *
 * <p>표가 비어 있으면 실행 계획이 실제와 달라질 수 있어, 이번 실행의 번호로 리뷰와 상품을 몇 개 넣고 뒤 정리에서 그 행만 지운다.
 */
class ReviewIndexUsageMySqlTest extends LocalMySqlTest {

	// information_schema.STATISTICS.NON_UNIQUE 값. 0 이면 UNIQUE 다.
	private static final int UNIQUE = 0;
	private static final int PAGE_SIZE = 5;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final long runNumber = Long.parseLong(runId, 16);
	// 실제 데이터와 겹치지 않게 큰 수에서 시작하고, 컬럼마다 다른 수에서 시작해 쿼리가 컬럼을 바꿔 써도 알아챌 수 있게 한다.
	// reviews 의 user_id·sub_category_id 와 subcategories 의 category_id 에는 외래 키가 없다.
	private final long subCategoryId = 7_000_000_000L + runNumber;
	private final long otherSubCategoryId = 7_100_000_000L + runNumber;
	private final long userId = 6_000_000_000L + runNumber;
	private final long otherUserId = 6_100_000_000L + runNumber;
	private final long firstOrderId = 5_000_000_000L + runNumber * 10;
	private final long categoryId = 4_000_000_000L + runNumber;

	static Stream<Arguments> reviewRepositoryQueries() {
		return Stream.of(
			Arguments.of("findReviewsBySubCategoryWithPagination", "idx_subcat_del_created", true),
			Arguments.of("countBySubCategory", "idx_subcat_del_created", true),
			Arguments.of("findAllReviewsWithPagination", "idx_del_created", true),
			Arguments.of("countAllReviews", "idx_del_created", true),
			// DELETE 는 지울 행을 찾은 뒤 그 행 전체를 지우므로 인덱스만 읽고 끝나지 않는다.
			Arguments.of("deleteAllByUserId", "idx_user_del_created", false));
	}

	@BeforeEach
	void saveReviewsAndProducts() {
		saveReview(firstOrderId, userId, subCategoryId, false, LocalDateTime.of(2026, 9, 1, 10, 0));
		saveReview(firstOrderId + 1, userId, otherSubCategoryId, false, LocalDateTime.of(2026, 9, 2, 10, 0));
		saveReview(firstOrderId + 2, otherUserId, subCategoryId, false, LocalDateTime.of(2026, 9, 3, 10, 0));
		saveReview(firstOrderId + 3, otherUserId, subCategoryId, true, LocalDateTime.of(2026, 9, 4, 10, 0));
		saveReview(firstOrderId + 4, otherUserId, otherSubCategoryId, false, LocalDateTime.of(2026, 9, 5, 10, 0));
		saveProduct("인생 총운", categoryId);
		saveProduct("궁합", categoryId);
		saveProduct("다른 분류 상품", categoryId + 1);
	}

	@AfterEach
	void deleteRowsOfThisRun() {
		jdbcTemplate.update("DELETE FROM reviews WHERE order_id BETWEEN ? AND ?", firstOrderId, firstOrderId + 4);
		jdbcTemplate.update("DELETE FROM subcategories WHERE category_id IN (?, ?)", categoryId, categoryId + 1);
	}

	@ParameterizedTest(name = "[{index}] {0} → {1}")
	@MethodSource("reviewRepositoryQueries")
	@DisplayName("ReviewRepository 의 네이티브 쿼리는 reviews 를 의도한 인덱스로 읽고 따로 정렬(filesort)하지 않는다")
	void reviewQueryUsesIndexWithoutFilesort(String repositoryMethod, String expectedIndex,
		boolean readsIndexOnly) {
		// given
		String sql = withValues(nativeQueryOf(repositoryMethod));

		// when
		List<Map<String, Object>> plan = jdbcTemplate.queryForList("EXPLAIN " + sql);

		// then: 페이지 쿼리의 바깥 JOIN 은 별칭 r 로 PRIMARY 를 읽으므로, 인덱스로 찾는 줄은 표 이름이 reviews 인 줄 하나다
		assertThat(plan).as("실행 계획 %s", plan)
			.filteredOn(row -> "reviews".equals(row.get("table")))
			.singleElement()
			.satisfies(row -> {
				assertThat(row.get("key")).as("쓰는 인덱스").isEqualTo(expectedIndex);
				assertThat(String.valueOf(row.get("Extra"))).as("인덱스만 읽고 끝나는지(Using index)")
					.matches(extra -> extra.contains("Using index") == readsIndexOnly);
			});
		assertThat(plan).as("실행 계획 %s", plan)
			.extracting(row -> String.valueOf(row.get("Extra")))
			.noneMatch(extra -> extra.contains("Using filesort"));
	}

	@Test
	@DisplayName("상품 목록 조회(WHERE category_id = ?)는 subcategories 를 idx_subcategories_category_id 로 찾는다")
	void productListUsesCategoryIndex() {
		// when: SubCategoryRepository.findAllByCategoryId 가 만드는 조건과 같은 SQL
		List<Map<String, Object>> plan = jdbcTemplate.queryForList(
			"EXPLAIN SELECT * FROM subcategories WHERE category_id = " + categoryId);

		// then: ref 는 인덱스에서 같은 값을 가진 행만 찾아 읽는다는 뜻이다(ALL 이면 표 전체를 훑는다)
		assertThat(plan).as("실행 계획 %s", plan)
			.singleElement()
			.satisfies(row -> {
				assertThat(row.get("key")).as("쓰는 인덱스").isEqualTo("idx_subcategories_category_id");
				assertThat(row.get("type")).as("접근 방식").isEqualTo("ref");
			});
	}

	@Test
	@DisplayName("주문 하나에 리뷰 하나를 지키는 uk_reviews_order_id 가 order_id 하나로 된 UNIQUE 로 테스트 DB 에 있다")
	void reviewOrderIdUniqueKeyExists() {
		// when
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
			"SELECT COLUMN_NAME, NON_UNIQUE FROM information_schema.STATISTICS"
				+ " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'reviews' AND INDEX_NAME = 'uk_reviews_order_id'"
				+ " ORDER BY SEQ_IN_INDEX");

		// then
		assertThat(rows)
			.as("reviews 에 uk_reviews_order_id 가 없다. 엔티티 선언이 있는데도 없다면, 같은 order_id 를 가진 행이 있어"
				+ " ddl-auto: update 가 UNIQUE 를 만들지 못한 것이다(기동은 멈추지 않는다). 중복 행을 지우고 다시 돌린다.")
			.singleElement()
			.satisfies(row -> {
				assertThat(row.get("COLUMN_NAME")).isEqualTo("order_id");
				assertThat(((Number) row.get("NON_UNIQUE")).intValue()).as("NON_UNIQUE").isEqualTo(UNIQUE);
			});
	}

	/** ReviewRepository 메서드의 @Query 에 적힌 네이티브 SQL 을 그대로 꺼낸다. */
	private static String nativeQueryOf(String repositoryMethod) {
		Query query = Arrays.stream(ReviewRepository.class.getDeclaredMethods())
			.filter(method -> method.getName().equals(repositoryMethod))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("ReviewRepository 에 " + repositoryMethod + " 가 없다."))
			.getAnnotation(Query.class);
		assertThat(query.nativeQuery()).as("%s 는 네이티브 쿼리다", repositoryMethod).isTrue();
		return query.value();
	}

	/** 이름 붙은 인자를 이번 실행의 값으로 바꾼다. 쿼리에 없는 인자는 그대로 지나간다. */
	private String withValues(String sql) {
		return sql.replace(":subCategoryId", String.valueOf(subCategoryId))
			.replace(":userId", String.valueOf(userId))
			.replace(":limit", String.valueOf(PAGE_SIZE))
			.replace(":offset", "0");
	}

	private void saveReview(long orderId, long reviewerId, long reviewedSubCategoryId, boolean deleted,
		LocalDateTime createdAt) {
		jdbcTemplate.update(
			"INSERT INTO reviews (user_id, sub_category_id, order_id, content, is_deleted, created_at, updated_at)"
				+ " VALUES (?, ?, ?, ?, ?, ?, ?)",
			reviewerId, reviewedSubCategoryId, orderId, "풀이가 자세하고 이해하기 쉬워서 많은 도움이 되었습니다.", deleted,
			createdAt, createdAt);
	}

	private void saveProduct(String title, long productCategoryId) {
		jdbcTemplate.update("INSERT INTO subcategories (title, price, category_id) VALUES (?, ?, ?)", title, 10000,
			productCategoryId);
	}
}
