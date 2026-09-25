package com.mansereok.server.domain.interpret.repository;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.mockingDetails;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.InterpretationMySqlTest;
import com.mansereok.server.support.fixture.ResultFixture;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결과 표 두 개(results, compatibility_results)에 이름을 고정한 제약·인덱스와 넓힌 본문 컬럼이 실제 MySQL 에서 약속대로
 * 동작하는지 확인한다.
 *
 * <p>운영은 ddl-auto: validate 라 UNIQUE·인덱스 이름과 TEXT 크기를 검사하지 않는다. 그래서 엔티티 선언이 MySQL 에 어떤 이름과
 * 컬럼 순서로 걸리는지, 같은 결제로 두 번째 행을 넣으면 DB 가 막는지, TEXT 한도를 넘는 본문이 그대로 저장되는지를 실제 MySQL 로
 * 본다. 탈퇴 삭제가 엔티티를 읽지 않고 DELETE 한 번으로 끝나는지는 Hibernate 통계로 세고, 그 DELETE 가 다른 사용자의 행까지
 * 잠그지 않는지는 삭제 트랜잭션을 열어 둔 채 다른 행을 고쳐 본다.
 *
 * <p>로컬 표를 예전 엔티티로 만들었다면 ddl-auto: update 가 고치지 못하는 부분이 있다. update 는 없는 인덱스를 더할 뿐 Hibernate
 * 가 지은 UK... 이름을 바꾸거나 TEXT 를 넓히지 않는다. 그런 표에서는 "MySQL 에 걸린 제약과 인덱스는" 묶음이 원인을 적어 실패한다.
 *
 * <p>두 리포지토리는 {@link MockitoSpyBean} 이다. 평소에는 진짜 리포지토리에 그대로 넘기고, 동시 생성 테스트에서만 결제 ID 조회
 * 뒤에 두 스레드를 맞춰 세운다. 그래서 이 클래스는 다른 해석 MySQL 테스트와 스프링 컨텍스트를 함께 쓰지 않는다.
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

	// 다른 행을 고치는 일은 잠금에 걸리지 않으면 수 밀리초에 끝난다. 걸리면 InnoDB 잠금 대기 기본값(50초)까지 멈춘다.
	private static final Duration OTHER_ROW_TIMEOUT = Duration.ofSeconds(2);
	private static final String FINISHED = "끝남";
	private static final String LOCK_SCOPE_FIX = "삭제 트랜잭션이 끝날 때까지 다른 사용자의 행이 기다렸다. DELETE ... WHERE user_id = ? 가 "
		+ "user_id 로 시작하는 인덱스 없이 표 전체를 훑으며 모든 행과 표 끝(supremum)을 잠근 것이다. 엔티티의 idx_*_user_id 선언과 "
		+ "로컬 표의 인덱스를 확인한다.";

	@MockitoSpyBean
	private ResultRepository resultRepository;

	@MockitoSpyBean
	private CompatibilityResultRepository compatibilityResultRepository;

	@Autowired
	private ResultService resultService;

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
			# 표,                  결제 ID UNIQUE 이름,                    (상태, 변경 시각) 인덱스 이름,                   사용자 ID 인덱스 이름
			results,               uk_results_payment_id,               idx_results_status_updated_at,               idx_results_user_id
			compatibility_results, uk_compatibility_results_payment_id, idx_compatibility_results_status_updated_at, idx_compatibility_results_user_id
			""")
		@DisplayName("엔티티에 고정한 이름과 컬럼 순서로 걸려 있고, payment_id 의 UNIQUE 는 그 이름 하나뿐이다")
		void namedIndexesAreInPlace(String table, String uniqueName, String statusIndexName, String userIdIndexName) {
			// when
			List<IndexColumn> indexColumns = indexColumnsOf(table);

			// then
			assertThat(indexColumns)
				.as("%s 의 인덱스가 엔티티 선언과 다르다. %s", table, LOCAL_TABLE_FIX)
				.contains(
					new IndexColumn(uniqueName, true, 1, "payment_id"),
					new IndexColumn(statusIndexName, false, 1, "status"),
					new IndexColumn(statusIndexName, false, 2, "updated_at"),
					new IndexColumn(userIdIndexName, false, 1, "user_id"));
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

		@ParameterizedTest(name = "[{index}] {0}")
		@CsvSource(textBlock = """
			# 결과 표,              상품 번호,    막는 UNIQUE
			# 19 는 ResultService 가 궁합 표로 보내는 상품 번호다. 900000001 은 그 목록 밖이라 사주 표로 간다.
			results,               900000001, results.uk_results_payment_id
			compatibility_results, 19,        compatibility_results.uk_compatibility_results_payment_id
			""")
		@DisplayName("같은 결제로 결과 행 만들기 두 개가 모두 '행 없음' 을 확인한 뒤 저장하면, 하나는 UNIQUE 위반으로 끝나고 행은 하나만 남는다")
		void concurrentCreateInitialResultLeavesOneRow(String table, long productId, String uniqueKey) {
			// given
			Order order = paidOrderOf(productId);
			Payment payment = paymentRepository.save(Payment.create("imp_" + runId, merchantUid, 10000L,
				PaymentStatus.PAID, order.getId(), userA, productId));
			bothCallsSeeNoResultBeforeSaving(payment.getId());

			// when
			List<CallResult<Void>> calls = ConcurrentCalls.runAtTheSameTime(2, () -> {
				resultService.createInitialResult(payment, order);
				return null;
			});

			// then
			assertThat(calls).filteredOn(CallResult::succeeded).as("성공한 요청").hasSize(1);
			assertThat(calls).filteredOn(call -> !call.succeeded()).as("실패한 요청").singleElement()
				.satisfies(call -> assertThat(call.error())
					.isInstanceOf(DataIntegrityViolationException.class)
					.rootCause()
					.hasMessageContaining(uniqueKey));
			assertThat(rowsOfPayment(table, payment.getId())).as("%s 에 남은 결제 %d 의 행", table, payment.getId())
				.isEqualTo(1);
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
			Result result = ResultFixture.saju(userA, paymentId, ResultStatus.PROCESSING);
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
			CompatibilityResult result = ResultFixture.compatibility(userA, paymentId, ResultStatus.PROCESSING);
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

		@Test
		@DisplayName("지운 트랜잭션이 끝나기 전에도 다른 사용자 결과의 상태 변경과 새 결과 행 저장은 기다리지 않고 끝난다")
		void doesNotLockOtherUsersRows() {
			// given
			saveSaju(userA, runKey + 1);
			saveSaju(userA, runKey + 2);
			saveSaju(userB, runKey + 3);

			// when
			Map<String, String> outcomes = outcomesWhileDeleteIsOpen(
				() -> resultRepository.deleteAllByUserId(userA),
				Map.of(
					"다른 사용자 결과를 해석 중으로 바꾸기", () -> resultService.startProcessing(runKey + 3),
					"다른 사용자의 새 결과 행 저장", () -> saveSaju(userB, runKey + 4)));

			// then
			assertThat(outcomes).as(LOCK_SCOPE_FIX).containsExactlyInAnyOrderEntriesOf(Map.of(
				"다른 사용자 결과를 해석 중으로 바꾸기", FINISHED,
				"다른 사용자의 새 결과 행 저장", FINISHED));
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

		@Test
		@DisplayName("지운 트랜잭션이 끝나기 전에도 다른 사용자 결과의 상태 변경과 새 결과 행 저장은 기다리지 않고 끝난다")
		void doesNotLockOtherUsersRows() {
			// given
			saveCompatibility(userA, runKey + 1);
			saveCompatibility(userA, runKey + 2);
			saveCompatibility(userB, runKey + 3);

			// when
			Map<String, String> outcomes = outcomesWhileDeleteIsOpen(
				() -> compatibilityResultRepository.deleteAllByUserId(userA),
				Map.of(
					"다른 사용자 결과를 해석 중으로 바꾸기", () -> resultService.startCompatibilityProcessing(runKey + 3),
					"다른 사용자의 새 결과 행 저장", () -> saveCompatibility(userB, runKey + 4)));

			// then
			assertThat(outcomes).as(LOCK_SCOPE_FIX).containsExactlyInAnyOrderEntriesOf(Map.of(
				"다른 사용자 결과를 해석 중으로 바꾸기", FINISHED,
				"다른 사용자의 새 결과 행 저장", FINISHED));
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
			// given: 저장한 행을 해석 중으로 두고 updated_at 을 과거로 옮겨 둔다
			Long paymentId = runKey;
			saveCompatibility(userA, paymentId);
			jdbcTemplate.update("UPDATE compatibility_results SET status = 'PROCESSING', updated_at = '2020-01-01 00:00:00' "
				+ "WHERE payment_id = ?", paymentId);

			// when: 엔티티를 읽어 상태를 바꾸는 길(조건부 UPDATE 가 아니라 @PreUpdate 를 거치는 길)로 고친다
			resultService.rollbackCompatibilityStatusByPaymentId(paymentId);

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
	 * 상품 번호를 정해 상품 행을 넣고 그 상품의 결제 완료 주문을 만든다. ResultService 는 상품 번호로 사주 표와 궁합 표를 가르므로,
	 * 자동 증가 값에 맡기지 않고 번호를 정한다.
	 */
	private Order paidOrderOf(long productId) {
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subcategories WHERE id = ?", Integer.class, productId))
			.as("상품 %d 가 이미 있다. 이전 실행이 남긴 테스트 상품이면 지우고 다시 돌린다", productId)
			.isZero();
		jdbcTemplate.update("INSERT INTO subcategories (id, title, price) VALUES (?, ?, ?)", productId, productTitle, 10000);
		return orderRepository.save(Order.create(merchantUid, userA, productId, 10000, 10000, null, null,
			OrderStatus.PAID, "구매자", "buyer@example.com"));
	}

	/**
	 * 결제 ID 로 결과 행을 찾는 호출이 두 번 모두 끝날 때까지 먼저 끝난 쪽을 세워 둔다. 두 요청이 모두 "행 없음" 을 본 뒤에 저장하므로,
	 * 두 번째 저장을 막는 것은 UNIQUE 뿐이다.
	 */
	private void bothCallsSeeNoResultBeforeSaving(Long paymentId) {
		CountDownLatch bothLookedUp = new CountDownLatch(2);
		Answer<Object> lookUpThenWaitForTheOther = invocation -> {
			Object found = callRealRepository(invocation);
			bothLookedUp.countDown();
			assertThat(bothLookedUp.await(10, SECONDS)).as("다른 요청도 결제 ID 로 결과 행을 찾았다").isTrue();
			return found;
		};
		willAnswer(lookUpThenWaitForTheOther).given(resultRepository).findByPaymentId(paymentId);
		willAnswer(lookUpThenWaitForTheOther).given(compatibilityResultRepository).findByPaymentId(paymentId);
	}

	/**
	 * 스파이가 평소 하는 일, 즉 진짜 리포지토리에 넘기기를 한다. 리포지토리 빈은 인터페이스의 프록시라 callRealMethod 를 쓸 수 없어,
	 * 스파이를 만들 때 정해진 기본 응답을 그대로 부른다.
	 */
	private static Object callRealRepository(InvocationOnMock invocation) throws Throwable {
		return mockingDetails(invocation.getMock()).getMockCreationSettings().getDefaultAnswer().answer(invocation);
	}

	/**
	 * 테스트 스레드에서 트랜잭션을 열어 deleteInOpenTransaction 을 부르고, 그 트랜잭션을 끝내기 전에 다른 스레드에서 otherWork 를
	 * 한꺼번에 돌린다. 작업마다 {@link #OTHER_ROW_TIMEOUT} 안에 끝났는지를 "끝남", "시간 초과", "실패: 예외" 로 돌려준다.
	 *
	 * <p>시간 초과로 멈춘 작업은 트랜잭션이 커밋되어 잠금이 풀리면 이어서 돈다. 그 작업이 뒤 정리보다 늦게 행을 남기지 않도록 돌려주기
	 * 전에 모두 끝나기를 기다린다.
	 */
	private Map<String, String> outcomesWhileDeleteIsOpen(Runnable deleteInOpenTransaction,
		Map<String, Runnable> otherWork) {
		ExecutorService executor = Executors.newFixedThreadPool(otherWork.size());
		try {
			return transactionTemplate.execute(status -> {
				deleteInOpenTransaction.run();
				Map<String, Future<?>> running = new LinkedHashMap<>();
				otherWork.forEach((name, work) -> running.put(name, executor.submit(work)));
				long deadline = System.nanoTime() + OTHER_ROW_TIMEOUT.toNanos();
				Map<String, String> outcomes = new LinkedHashMap<>();
				running.forEach((name, future) -> outcomes.put(name, outcomeBefore(future, deadline)));
				return outcomes;
			});
		} finally {
			executor.shutdown();
			awaitTermination(executor);
		}
	}

	private static String outcomeBefore(Future<?> future, long deadline) {
		try {
			future.get(Math.max(0, deadline - System.nanoTime()), NANOSECONDS);
			return FINISHED;
		} catch (TimeoutException e) {
			return "시간 초과";
		} catch (ExecutionException e) {
			return "실패: " + e.getCause();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("다른 행 작업을 기다리던 테스트 스레드가 중단됐다.", e);
		}
	}

	private static void awaitTermination(ExecutorService executor) {
		try {
			if (!executor.awaitTermination(60, SECONDS)) {
				throw new IllegalStateException("삭제 트랜잭션을 끝낸 뒤에도 다른 행 작업이 60초 안에 끝나지 않았다. DB 에 행이 남았는지 확인한다.");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("다른 행 작업이 끝나기를 기다리던 테스트 스레드가 중단됐다.", e);
		}
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

	private int rowsOfPayment(String table, Long paymentId) {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE payment_id = ?",
			Integer.class, paymentId);
		return count == null ? 0 : count;
	}
}
