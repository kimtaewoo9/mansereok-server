package com.mansereok.server.domain.order.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.UniqueConstraintViolations;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.TestOrders;
import com.mansereok.server.support.fixture.TestPayments;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문·결제의 행 잠금과 조회가 기대는 UNIQUE·인덱스가 실제 MySQL 에 걸려 있고, 그 위에서 조회와 제약이 기대대로 도는지 확인한다.
 *
 * <p>테스트 DB 는 ddl-auto: update 라 Order·Payment 의 @Table 선언대로 UNIQUE·인덱스가 생긴다. 운영은 validate 라 같은 이름의
 * DDL 을 손으로 적용한다. 그래서 여기서 보는 이름과 컬럼 순서가 운영에 적용할 DDL 과 schema.sql 의 기준이 된다. 엔티티와
 * schema.sql 이 그 이름을 쓰는지는 PaymentSchemaSqlTest 가 DB 없이 본다.
 *
 * <p>주문·결제·사용자는 실행마다 다른 키(runId)로 만들고 그 키로만 지운다.
 */
class OrderPaymentIndexMySqlTest extends PaymentMySqlTest {

	// 상품 id 는 결과에 영향이 없다. orders.sub_category_id 는 subcategories 를 참조하지 않는다.
	private static final Long ANY_SUB_CATEGORY_ID = 1L;
	private static final int PRICE = 10000;

	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private PaymentRepository paymentRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private UserService userService;
	@Autowired
	private PlatformTransactionManager transactionManager;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String merchantUidPrefix = "order_index_" + runId + "_";
	private final String username = "order_index_" + runId;
	private final String impUid = "pay_order_index_" + runId;
	// orders.payment_pk_id 는 payments 를 참조하지 않는다. 실제 결제 PK(작은 수)와 겹치지 않게 큰 수에 실행 키를 더한다.
	private final Long paymentPkId = 1_000_000_000_000L + Long.parseLong(runId, 16);

	@AfterEach
	void deleteRowsOfThisRun() {
		jdbcTemplate.update("DELETE FROM payments WHERE imp_uid = ?", impUid);
		jdbcTemplate.update("DELETE FROM orders WHERE merchant_uid LIKE ?", merchantUidPrefix + "%");
		jdbcTemplate.update("DELETE FROM users WHERE username = ?", username);
	}

	@Nested
	@DisplayName("엔티티에 선언한 UNIQUE·인덱스는")
	class DeclaredIndexes {

		// information_schema.STATISTICS.NON_UNIQUE 값. 0 이면 UNIQUE 다.
		private static final int UNIQUE = 0;
		private static final int NON_UNIQUE = 1;

		static Stream<Arguments> declaredIndexes() {
			return Stream.of(
				Arguments.of("orders", "uk_orders_merchant_uid", List.of("merchant_uid"), UNIQUE),
				Arguments.of("orders", "uk_orders_payment_pk_id", List.of("payment_pk_id"), UNIQUE),
				Arguments.of("orders", "idx_orders_status_created_at", List.of("status", "created_at"), NON_UNIQUE),
				Arguments.of("orders", "idx_orders_coupon_id", List.of("coupon_id"), NON_UNIQUE),
				Arguments.of("orders", "idx_orders_user_id", List.of("user_id"), NON_UNIQUE),
				Arguments.of("payments", "idx_payments_created_at", List.of("created_at"), NON_UNIQUE),
				Arguments.of("payments", "idx_payments_status", List.of("status"), NON_UNIQUE),
				Arguments.of("payments", "idx_payments_user_id_created_at", List.of("user_id", "created_at"),
					NON_UNIQUE));
		}

		@ParameterizedTest(name = "[{index}] {0}.{1} {2} NON_UNIQUE={3}")
		@MethodSource("declaredIndexes")
		@DisplayName("선언한 이름·컬럼 순서·UNIQUE 여부 그대로 테스트 DB 에 있다")
		void existInTestDatabaseAsDeclared(String table, String indexName, List<String> columns, int nonUnique) {
			// when
			List<Map<String, Object>> rows = jdbcTemplate.queryForList(
				"SELECT COLUMN_NAME, NON_UNIQUE FROM information_schema.STATISTICS"
					+ " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ? ORDER BY SEQ_IN_INDEX",
				table, indexName);

			// then
			assertThat(rows)
				.as("%s 에 %s 가 없다. 엔티티 @Table 선언이 있는데도 없다면, UNIQUE 는 표에 중복 행이 있어 ddl-auto: update 가 만들지"
					+ " 못한 것이다(기동은 멈추지 않는다). 테스트 DB 의 %s 에서 중복 행을 지우고 다시 돌린다.", table, indexName, table)
				.isNotEmpty();
			assertThat(rows)
				.as("%s 의 컬럼 순서. 같은 이름이 이미 있으면 ddl-auto: update 는 다시 만들지 않으므로, 선언을 바꿨다면 테스트 DB 에서"
					+ " 그 이름을 지우고 다시 돌린다.", indexName)
				.extracting(row -> row.get("COLUMN_NAME"))
				.containsExactlyElementsOf(columns);
			assertThat(rows).as("%s 의 NON_UNIQUE", indexName)
				.extracting(row -> ((Number) row.get("NON_UNIQUE")).intValue())
				.containsOnly(nonUnique);
		}
	}

	@Nested
	@DisplayName("만료 대상 조회는")
	class ExpirationScan {

		// 다른 테스트가 남긴 주문과 겹치지 않도록 먼 과거로 둔다.
		private final LocalDateTime cutoff = LocalDateTime.of(2001, 2, 3, 4, 5, 6);

		/**
		 * 만료 스케줄러는 30분보다 오래된 PENDING 주문을 만료시킨다. 이 테스트의 주문을 커밋하면 그사이 스케줄러가 돌 때 EXPIRED 로
		 * 바뀔 수 있으므로, 준비와 조회를 커밋하지 않는 트랜잭션 하나에서 하고 끝나면 되돌린다. 다른 트랜잭션인 스케줄러는 커밋하지 않은
		 * 행을 보지 못한다.
		 */
		@Test
		@DisplayName("기준 시각보다 먼저 만든 PENDING 주문의 id 만 돌려주고, 기준 시각과 같거나 늦은 주문과 다른 상태의 주문은 뺀다")
		void returnsOnlyPendingOrderIdsCreatedBeforeCutoff() {
			inTransactionRolledBackAtEnd(() -> {
				// given
				Long pendingBeforeCutoff = saveOrderCreatedAt("pending_before", OrderStatus.PENDING,
					cutoff.minusSeconds(1));
				saveOrderCreatedAt("pending_at", OrderStatus.PENDING, cutoff);
				saveOrderCreatedAt("pending_after", OrderStatus.PENDING, cutoff.plusSeconds(1));
				saveOrderCreatedAt("virtual_account", OrderStatus.VIRTUAL_ACCOUNT_ISSUED, cutoff.minusHours(1));
				saveOrderCreatedAt("paid", OrderStatus.PAID, cutoff.minusHours(1));

				// when
				List<Long> ids = orderRepository.findIdsByStatusAndCreatedAtBefore(OrderStatus.PENDING, cutoff);

				// then
				assertThat(ids).containsExactly(pendingBeforeCutoff);
			});
		}

		/**
		 * 주문을 저장한 뒤 상태와 created_at 을 DB 에서 옮긴다. 저장할 때는 @PrePersist 가 created_at 을 지금 시각으로 덮는다. 상태는
		 * VIRTUAL_ACCOUNT_ISSUED 처럼 운영 코드의 전이로 갈 수 없는 상태도 넣으려고 DB 에서 바꾼다.
		 */
		private Long saveOrderCreatedAt(String name, OrderStatus status, LocalDateTime createdAt) {
			Long id = orderRepository.saveAndFlush(order(name, OrderStatus.PENDING)).getId();
			jdbcTemplate.update("UPDATE orders SET status = ?, created_at = ? WHERE id = ?", status.name(), createdAt,
				id);
			return id;
		}

		private void inTransactionRolledBackAtEnd(Runnable work) {
			new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
				transaction.setRollbackOnly();
				work.run();
			});
		}
	}

	@Nested
	@DisplayName("결제 PK 로 주문을 찾을 때")
	class PaymentPkLookup {

		@Test
		@DisplayName("그 결제가 붙은 주문 하나만 나온다")
		void findsTheOneOrderLinkedToPayment() {
			// given: 결제가 붙지 않은 두 주문은 payment_pk_id 가 NULL 이다. MySQL 의 UNIQUE 는 NULL 을 여러 개 허용한다
			orderRepository.saveAndFlush(order("first", OrderStatus.PENDING));
			Long linkedOrderId = orderRepository.saveAndFlush(orderLinkedToPayment("linked")).getId();
			orderRepository.saveAndFlush(order("third", OrderStatus.PENDING));

			// when
			Order found = orderRepository.findByPaymentPkId(paymentPkId).orElseThrow();

			// then
			assertThat(found.getId()).isEqualTo(linkedOrderId);
		}

		@Test
		@DisplayName("같은 결제 PK 를 두 주문에 붙이면 두 번째 저장이 uk_orders_payment_pk_id 에 걸려 UNIQUE 위반으로 판별되고 한 주문만 남는다")
		void secondOrderWithSamePaymentPkIsRejected() {
			// given
			orderRepository.saveAndFlush(orderLinkedToPayment("first"));

			// when
			Throwable thrown = catchThrowable(
				() -> orderRepository.saveAndFlush(orderLinkedToPayment("second")));

			// then
			assertThat(thrown).isInstanceOfSatisfying(DataIntegrityViolationException.class, e -> {
				assertThat(UniqueConstraintViolations.isUniqueViolation(e)).as("UNIQUE 위반으로 판별한다").isTrue();
				assertThat(e.getMostSpecificCause()).as("MySQL 이 알려 준 제약 이름")
					.hasMessageContaining("uk_orders_payment_pk_id");
			});
			assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders WHERE payment_pk_id = ?",
				Integer.class, paymentPkId))
				.isEqualTo(1);
		}
	}

	/**
	 * 이 테스트가 잡는 것은 탈퇴가 결제의 사용자 연결을 끊지 않는 회귀(UserService 에서 detachUser 호출이 빠지는 것)다.
	 *
	 * <p>Payment.userId 의 NULL 허용 선언은 PaymentSchemaSqlTest 가 지킨다. ddl-auto: update 는 이미 있는 컬럼의 NULL 허용을
	 * 바꾸지 않으므로, 선언을 nullable = false 로 되돌려도 이미 있는 테스트 DB 에서는 이 테스트가 통과한다. 그렇게 되돌린 채 새로
	 * 만든 DB 에서만 컬럼이 NOT NULL 로 생겨, 이 테스트가 전제 조건(IS_NULLABLE = YES)에서 실패한다.
	 */
	@Nested
	@DisplayName("결제 이력이 있는 사용자가 탈퇴하면")
	class Withdrawal {

		@Test
		@DisplayName("결제 행은 남고 사용자 id 만 NULL 로 비워진다")
		void keepsPaymentAndClearsUserId() {
			// given: 테스트 DB 의 payments.user_id 가 NULL 을 허용해야 탈퇴의 UPDATE 가 들어간다
			assertThat(jdbcTemplate.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS"
					+ " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'user_id'", String.class))
				.as("테스트 DB 의 payments.user_id 가 NOT NULL 이다. 엔티티 Payment.userId 를 nullable = false 로 바꿨다면"
					+ " PaymentSchemaSqlTest 도 함께 실패한다. 엔티티는 NULL 허용인데 이 값이 NO 라면 ddl-auto: update 가 이미 있는 컬럼의"
					+ " NULL 허용을 바꾸지 않은 것이므로, 테스트 DB 에 ALTER TABLE payments MODIFY user_id BIGINT NULL 을 적용하고 다시 돌린다.")
				.isEqualTo("YES");
			Long userId = userRepository.save(User.create(username, "탈퇴", "password", username + "@example.com",
				LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false)).getId();
			paymentRepository.saveAndFlush(TestPayments.payment().paymentId(impUid)
				.merchantUid(merchantUidPrefix + "withdrawn").orderId(1L).userId(userId).subCategoryId(ANY_SUB_CATEGORY_ID)
				.amount(PRICE).paid());

			// when
			userService.deleteUser(username);

			// then
			assertThat(jdbcTemplate.queryForList("SELECT user_id FROM payments WHERE imp_uid = ?", impUid))
				.as("탈퇴 뒤 남은 결제 행")
				.singleElement()
				.satisfies(row -> assertThat(row.get("user_id")).as("결제의 사용자 id").isNull());
		}
	}

	private Order order(String name, OrderStatus status) {
		return TestOrders.order().merchantUid(merchantUidPrefix + name).userId(null).subCategoryId(ANY_SUB_CATEGORY_ID)
			.price(PRICE).inStatus(status);
	}

	private Order orderLinkedToPayment(String name) {
		Order order = order(name, OrderStatus.PAID);
		order.linkPayment(paymentPkId);
		return order;
	}
}
