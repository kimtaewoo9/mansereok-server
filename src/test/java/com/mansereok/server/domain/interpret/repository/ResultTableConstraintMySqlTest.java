package com.mansereok.server.domain.interpret.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.InterpretationMySqlTest;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결과 표 두 개(results, compatibility_results)에 이름을 고정한 제약·인덱스와 넓힌 본문 컬럼이 실제 MySQL 에서 약속대로
 * 동작하는지 확인한다.
 *
 * <p>운영은 ddl-auto: validate 라 UNIQUE·인덱스 이름과 TEXT 크기를 검사하지 않는다. 그래서 엔티티 선언이 MySQL 에 어떤 이름과
 * 컬럼 순서로 걸리는지, 같은 결제로 두 번째 행을 넣으면 DB 가 막는지, TEXT 한도를 넘는 본문이 그대로 저장되는지를 실제 MySQL 로
 * 본다. 탈퇴 삭제가 엔티티를 읽지 않고 DELETE 한 번으로 끝나는지는 Hibernate 통계로 센다.
 *
 * <p>로컬 표를 예전 엔티티로 만들었다면 ddl-auto: update 가 고치지 못하는 부분이 있다. update 는 없는 인덱스를 더할 뿐 Hibernate
 * 가 지은 UK... 이름을 바꾸거나 TEXT 를 넓히지 않는다. 그런 표에서는 "MySQL 에 걸린 제약과 인덱스는" 묶음이 원인을 적어 실패한다.
 *
 * <p>결과 행은 이번 실행의 사용자 ID 로 만들고 뒤 정리에서 그 사용자 ID 로만 지운다. 결과 표는 사용자·결제 표를 참조하지 않으므로,
 * 결제 확정 흐름을 흉내 내는 동시 요청 테스트만 상품·주문·결제 행을 만든다.
 */
class ResultTableConstraintMySqlTest extends InterpretationMySqlTest {

	private static final String LOCAL_TABLE_FIX = "로컬 표가 예전 엔티티로 만들어졌을 수 있다. ddl-auto: update 는 Hibernate 가 "
		+ "지은 UK... 인덱스 이름을 바꾸거나 TEXT 컬럼을 넓히지 않는다. PR 본문의 운영 DDL 을 로컬 DB 에 돌리거나, manses 를 뺀 "
		+ "results·compatibility_results 표를 지우고 다시 돌린다.";

	// 한글 25,000자는 utf8mb4 로 75,000바이트라 TEXT 한도(65,535바이트)를 넘는다.
	private static final String LONG_KOREAN_TEXT = "가나다라마바사아자차".repeat(2_500);

	@Autowired
	private ResultRepository resultRepository;

	@Autowired
	private CompatibilityResultRepository compatibilityResultRepository;

	@Autowired
	private ResultService resultService;

	@Autowired
	private SubCategoryRepository subCategoryRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private TransactionTemplate transactionTemplate;

	// 실행마다 다른 값이라 이전 실행이 남긴 행과 사용자 ID·결제 ID(UNIQUE)가 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final long runKey = Long.parseLong(runId, 16) * 100;
	private final Long userA = runKey;
	private final Long userB = runKey + 1;
	private final String merchantUid = "order_result_table_" + runId;
	private final String productTitle = "결과 표 제약 " + runId;

	@AfterEach
	void stopCountingStatements() {
		statistics().setStatisticsEnabled(false);
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		jdbcTemplate.update("DELETE FROM results WHERE user_id IN (?, ?)", userA, userB);
		jdbcTemplate.update("DELETE FROM compatibility_results WHERE user_id IN (?, ?)", userA, userB);
		jdbcTemplate.update("DELETE FROM payments WHERE merchant_uid = ?", merchantUid);
		jdbcTemplate.update("DELETE FROM orders WHERE merchant_uid = ?", merchantUid);
		jdbcTemplate.update("DELETE FROM subcategories WHERE title = ?", productTitle);
	}

	@Nested
	@DisplayName("MySQL 에 걸린 제약과 인덱스는")
	class DatabaseSchema {

		@ParameterizedTest(name = "[{index}] {0}")
		@CsvSource(textBlock = """
			# 표,                  결제 ID UNIQUE 이름,                    (상태, 변경 시각) 인덱스 이름
			results,               uk_results_payment_id,               idx_results_status_updated_at
			compatibility_results, uk_compatibility_results_payment_id, idx_compatibility_results_status_updated_at
			""")
		@DisplayName("엔티티에 고정한 이름과 컬럼 순서로 걸려 있고, payment_id 의 UNIQUE 는 그 이름 하나뿐이다")
		void namedIndexesAreInPlace(String table, String uniqueName, String statusIndexName) {
			// when
			List<IndexColumn> indexColumns = indexColumnsOf(table);

			// then
			assertThat(indexColumns)
				.as("%s 의 인덱스가 엔티티 선언과 다르다. %s", table, LOCAL_TABLE_FIX)
				.contains(
					new IndexColumn(uniqueName, true, 1, "payment_id"),
					new IndexColumn(statusIndexName, false, 1, "status"),
					new IndexColumn(statusIndexName, false, 2, "updated_at"));
			assertThat(indexColumns)
				.filteredOn(indexColumn -> indexColumn.unique() && indexColumn.column().equals("payment_id"))
				.extracting(IndexColumn::indexName)
				.as("%s.payment_id 에 걸린 UNIQUE 이름. %s", table, LOCAL_TABLE_FIX)
				.containsExactly(uniqueName);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@ValueSource(strings = {"results", "compatibility_results"})
		@DisplayName("본문(interpretation) 컬럼은 MEDIUMTEXT 다")
		void interpretationIsMediumText(String table) {
			// when
			String dataType = jdbcTemplate.queryForObject("SELECT DATA_TYPE FROM information_schema.COLUMNS "
					+ "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'interpretation'",
				String.class, table);

			// then
			assertThat(dataType).as("%s.interpretation 타입. %s", table, LOCAL_TABLE_FIX).isEqualTo("mediumtext");
		}
	}

	@Nested
	@DisplayName("같은 결제 ID 로 결과 행을 또 넣으면")
	class SamePaymentId {

		@Test
		@DisplayName("사주 결과는 uk_results_payment_id 에 막혀 DataIntegrityViolationException 이 난다")
		void secondSajuRowIsRejected() {
			// given
			Long paymentId = runKey;
			resultRepository.saveAndFlush(Result.createInitial(userA, paymentId, "사주"));

			// when & then
			assertThatThrownBy(() -> resultRepository.saveAndFlush(Result.createInitial(userA, paymentId, "사주")))
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("results.uk_results_payment_id");
		}

		@Test
		@DisplayName("궁합 결과는 uk_compatibility_results_payment_id 에 막혀 DataIntegrityViolationException 이 난다")
		void secondCompatibilityRowIsRejected() {
			// given
			Long paymentId = runKey;
			compatibilityResultRepository.saveAndFlush(CompatibilityResult.createInitial(userA, paymentId, "궁합"));

			// when & then
			assertThatThrownBy(() -> compatibilityResultRepository.saveAndFlush(
				CompatibilityResult.createInitial(userA, paymentId, "궁합")))
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("compatibility_results.uk_compatibility_results_payment_id");
		}

		@Test
		@DisplayName("같은 결제·주문으로 결과 행 만들기가 두 스레드에서 동시에 돌아도 행은 하나만 남고, 늦은 쪽은 건너뛰거나 UNIQUE 위반으로 끝난다")
		void concurrentCreateInitialResultLeavesOneRow() {
			// given: 상품 번호에 따라 사주 표나 궁합 표 중 하나에 들어가므로 두 표를 합쳐 센다
			Long subCategoryId = subCategoryRepository.save(
				SubCategoryFixture.paidProduct().withoutId().title(productTitle).build()).getId();
			Order order = orderRepository.save(Order.create(merchantUid, userA, subCategoryId, 10000, 10000, null,
				null, OrderStatus.PAID, "구매자", "buyer@example.com"));
			Payment payment = paymentRepository.save(Payment.create("imp_" + runId, merchantUid, 10000L,
				PaymentStatus.PAID, order.getId(), userA, subCategoryId));

			// when
			List<CallResult<Void>> calls = ConcurrentCalls.runAtTheSameTime(2, () -> {
				resultService.createInitialResult(payment, order);
				return null;
			});

			// then
			assertThat(calls).as("요청마다 성공하거나 UNIQUE 위반으로 끝난다").allSatisfy(call ->
				assertThat(call.error()).satisfiesAnyOf(
					error -> assertThat(error).isNull(),
					error -> assertThat(error).isInstanceOf(DataIntegrityViolationException.class)));
			assertThat(calls).filteredOn(CallResult::succeeded).as("성공한 요청").isNotEmpty();
			assertThat(resultRowsOfPayment(payment.getId())).as("결제 %d 의 결과 행", payment.getId()).isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("해석 본문이 TEXT 한도(65,535바이트)를 넘어도")
	class LongInterpretation {

		@Test
		@DisplayName("한글 25,000자 사주 해석문이 잘리지 않고 그대로 저장된다")
		void sajuInterpretationIsStoredWhole() {
			// given
			Long paymentId = runKey;
			Result result = Result.createInitial(userA, paymentId, "사주");
			result.completeInterpretation(LONG_KOREAN_TEXT, "요약");

			// when
			resultRepository.saveAndFlush(result);

			// then
			String stored = jdbcTemplate.queryForObject("SELECT interpretation FROM results WHERE payment_id = ?",
				String.class, paymentId);
			assertThat(stored.length()).as("DB 에서 다시 읽은 글자 수").isEqualTo(25_000);
			assertThat(stored).as("DB 에서 다시 읽은 본문").isEqualTo(LONG_KOREAN_TEXT);
		}

		@Test
		@DisplayName("한글 25,000자 궁합 해석문이 잘리지 않고 그대로 저장된다")
		void compatibilityInterpretationIsStoredWhole() {
			// given
			Long paymentId = runKey;
			CompatibilityResult result = CompatibilityResult.createInitial(userA, paymentId, "궁합");
			result.completeInterpretation(LONG_KOREAN_TEXT, 80, "요약");

			// when
			compatibilityResultRepository.saveAndFlush(result);

			// then
			String stored = jdbcTemplate.queryForObject(
				"SELECT interpretation FROM compatibility_results WHERE payment_id = ?", String.class, paymentId);
			assertThat(stored.length()).as("DB 에서 다시 읽은 글자 수").isEqualTo(25_000);
			assertThat(stored).as("DB 에서 다시 읽은 본문").isEqualTo(LONG_KOREAN_TEXT);
		}
	}

	@Nested
	@DisplayName("회원 탈퇴로 사주 결과 deleteAllByUserId 를 부르면")
	class DeleteSajuResultsOfUser {

		@Test
		@DisplayName("그 사용자의 결과만 지우고 다른 사용자의 결과는 남긴다")
		void deletesOnlyThatUsersRows() {
			// given
			saveSaju(userA, runKey + 1);
			saveSaju(userA, runKey + 2);
			saveSaju(userB, runKey + 3);

			// when
			transactionTemplate.executeWithoutResult(status -> resultRepository.deleteAllByUserId(userA));

			// then
			assertThat(rowsOfUser("results")).containsExactly(Map.of("user_id", userB, "payment_id", runKey + 3));
		}

		@Test
		@DisplayName("엔티티를 읽지 않고 DELETE 문 하나로 지운다")
		void deletesWithOneStatementWithoutLoadingEntities() {
			// given
			saveSaju(userA, runKey + 1);
			saveSaju(userA, runKey + 2);
			saveSaju(userA, runKey + 3);
			Statistics statistics = startCountingStatements();

			// when
			transactionTemplate.executeWithoutResult(status -> resultRepository.deleteAllByUserId(userA));

			// then
			assertThat(statistics.getEntityStatistics(Result.class.getName()).getLoadCount())
				.as("읽어 들인 Result 엔티티 수").isZero();
			assertThat(statistics.getEntityStatistics(Result.class.getName()).getDeleteCount())
				.as("엔티티 단위로 지운 Result 수").isZero();
			assertThat(statistics.getPrepareStatementCount()).as("DB 로 보낸 SQL 문 수").isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("회원 탈퇴로 궁합 결과 deleteAllByUserId 를 부르면")
	class DeleteCompatibilityResultsOfUser {

		@Test
		@DisplayName("그 사용자의 결과만 지우고 다른 사용자의 결과는 남긴다")
		void deletesOnlyThatUsersRows() {
			// given
			saveCompatibility(userA, runKey + 1);
			saveCompatibility(userA, runKey + 2);
			saveCompatibility(userB, runKey + 3);

			// when
			transactionTemplate.executeWithoutResult(status -> compatibilityResultRepository.deleteAllByUserId(userA));

			// then
			assertThat(rowsOfUser("compatibility_results"))
				.containsExactly(Map.of("user_id", userB, "payment_id", runKey + 3));
		}

		@Test
		@DisplayName("엔티티를 읽지 않고 DELETE 문 하나로 지운다")
		void deletesWithOneStatementWithoutLoadingEntities() {
			// given
			saveCompatibility(userA, runKey + 1);
			saveCompatibility(userA, runKey + 2);
			saveCompatibility(userA, runKey + 3);
			Statistics statistics = startCountingStatements();

			// when
			transactionTemplate.executeWithoutResult(status -> compatibilityResultRepository.deleteAllByUserId(userA));

			// then
			assertThat(statistics.getEntityStatistics(CompatibilityResult.class.getName()).getLoadCount())
				.as("읽어 들인 CompatibilityResult 엔티티 수").isZero();
			assertThat(statistics.getEntityStatistics(CompatibilityResult.class.getName()).getDeleteCount())
				.as("엔티티 단위로 지운 CompatibilityResult 수").isZero();
			assertThat(statistics.getPrepareStatementCount()).as("DB 로 보낸 SQL 문 수").isEqualTo(1);
		}
	}

	@Nested
	@DisplayName("궁합 결과의 updated_at 은")
	class CompatibilityUpdatedAt {

		@Test
		@DisplayName("처음 저장하면 created_at 과 같은 값으로 채워진다")
		void isFilledOnInsert() {
			// given
			Long paymentId = runKey;

			// when
			saveCompatibility(userA, paymentId);

			// then
			Map<String, Object> row = jdbcTemplate.queryForMap(
				"SELECT created_at, updated_at FROM compatibility_results WHERE payment_id = ?", paymentId);
			assertThat(row.get("updated_at")).isNotNull().isEqualTo(row.get("created_at"));
		}

		@Test
		@DisplayName("엔티티를 고쳐 저장하면 새 시각으로 바뀐다")
		void isRefreshedOnUpdate() {
			// given: 저장한 행의 updated_at 을 과거로 옮겨 둔다
			Long paymentId = runKey;
			saveCompatibility(userA, paymentId);
			jdbcTemplate.update("UPDATE compatibility_results SET updated_at = '2020-01-01 00:00:00' WHERE payment_id = ?",
				paymentId);

			// when
			resultService.updateCompatibilityStatusToProcessing(paymentId);

			// then
			LocalDateTime updatedAt = jdbcTemplate.queryForObject(
				"SELECT updated_at FROM compatibility_results WHERE payment_id = ?", LocalDateTime.class, paymentId);
			assertThat(updatedAt).isAfter(LocalDateTime.of(2020, 1, 1, 0, 0));
		}
	}

	/** information_schema.STATISTICS 의 한 줄. 인덱스 하나의 컬럼 하나와 그 자리(1부터)를 담는다. */
	private record IndexColumn(String indexName, boolean unique, int position, String column) {

	}

	private List<IndexColumn> indexColumnsOf(String table) {
		return jdbcTemplate.query("SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME "
				+ "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? "
				+ "ORDER BY INDEX_NAME, SEQ_IN_INDEX",
			(rs, rowNum) -> new IndexColumn(rs.getString("INDEX_NAME"), rs.getInt("NON_UNIQUE") == 0,
				rs.getInt("SEQ_IN_INDEX"), rs.getString("COLUMN_NAME")),
			table);
	}

	/**
	 * Hibernate 통계를 켜고 0 으로 되돌린다. 통계는 애플리케이션 전체에 하나라 뒤 정리에서 다시 끈다.
	 */
	private Statistics startCountingStatements() {
		Statistics statistics = statistics();
		statistics.setStatisticsEnabled(true);
		statistics.clear();
		return statistics;
	}

	private Statistics statistics() {
		return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
	}

	private void saveSaju(Long userId, Long paymentId) {
		resultRepository.save(Result.createInitial(userId, paymentId, "사주"));
	}

	private void saveCompatibility(Long userId, Long paymentId) {
		compatibilityResultRepository.save(CompatibilityResult.createInitial(userId, paymentId, "궁합"));
	}

	/** 이번 실행의 두 사용자가 가진 행을 (user_id, payment_id) 로 돌려준다. */
	private List<Map<String, Object>> rowsOfUser(String table) {
		return jdbcTemplate.queryForList("SELECT user_id, payment_id FROM " + table
			+ " WHERE user_id IN (?, ?) ORDER BY payment_id", userA, userB);
	}

	/** 사주 표와 궁합 표를 합쳐 그 결제의 결과 행을 센다. */
	private int resultRowsOfPayment(Long paymentId) {
		Integer count = jdbcTemplate.queryForObject("SELECT (SELECT COUNT(*) FROM results WHERE payment_id = ?) "
			+ "+ (SELECT COUNT(*) FROM compatibility_results WHERE payment_id = ?)", Integer.class, paymentId, paymentId);
		return count == null ? 0 : count;
	}
}
