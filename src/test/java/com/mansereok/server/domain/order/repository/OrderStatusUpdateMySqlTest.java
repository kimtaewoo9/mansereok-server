package com.mansereok.server.domain.order.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.TestOrders;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.EnumSource.Mode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 상태의 조건부 UPDATE(OrderRepository#updateStatusIf)가 실제 MySQL 에서 지금 상태가 기대한 값일 때만 바꾸는지 확인한다.
 *
 * <p>주문 만료(OrderExpirationService)는 이 UPDATE 가 바꾼 행 수(0 또는 1)만 보고 할인을 되돌릴지 정한다. "지금 상태가 PENDING 일
 * 때만" 이라는 조건이 빠지면 결제가 끝난 주문을 EXPIRED 로 덮어쓰고 할인까지 되돌린다. OrderExpirationServiceTest 는 바꾼 행 수를
 * 목으로 정해 두므로 이 조건이 빠져도 알아채지 못한다.
 *
 * <p>주문은 저장소로 바로 만들고, 실행마다 다른 주문 번호(runId)로 만들어 그 번호로만 지운다.
 */
class OrderStatusUpdateMySqlTest extends PaymentMySqlTest {

	// 상품·사용자 id 는 결과에 영향이 없다. orders 는 subcategories·users 를 참조하지 않는다.
	private static final Long ANY_USER_ID = 1L;
	private static final Long ANY_SUB_CATEGORY_ID = 3L;
	private static final int PRICE = 10000;

	@Autowired
	private OrderRepository orderRepository;
	@Autowired
	private PlatformTransactionManager transactionManager;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String merchantUid = "order_status_update_" + runId;

	@AfterEach
	void deleteOrderOfThisRun() {
		jdbcTemplate.update("DELETE FROM orders WHERE merchant_uid = ?", merchantUid);
	}

	@Nested
	@DisplayName("주문이 PENDING 이면")
	class WhenPending {

		@Test
		@DisplayName("PENDING 일 때만 EXPIRED 로 바꾸라고 하면 1행을 바꾸고 DB 에 EXPIRED 가 남는다")
		void changesOneRow() {
			// given
			Long orderId = saveOrder(OrderStatus.PENDING);

			// when
			int changedRows = expireIfPendingInTransaction(orderId);

			// then
			assertThat(changedRows).as("바꾼 행 수").isEqualTo(1);
			assertThat(statusInDatabase()).isEqualTo("EXPIRED");
		}

		@Test
		@DisplayName("같은 트랜잭션에서 먼저 읽어 둔 주문도 UPDATE 뒤에 다시 읽으면 EXPIRED 로 보인다")
		void rereadInSameTransactionSeesExpired() {
			// given
			Long orderId = saveOrder(OrderStatus.PENDING);

			// when: 주문을 먼저 읽어 영속성 컨텍스트에 PENDING 으로 올려 둔 뒤 UPDATE 하고 다시 읽는다
			OrderStatus reread = new TransactionTemplate(transactionManager).execute(status -> {
				orderRepository.findById(orderId).orElseThrow();
				orderRepository.updateStatusIf(orderId, OrderStatus.PENDING, OrderStatus.EXPIRED);
				return orderRepository.findById(orderId).orElseThrow().getStatus();
			});

			// then: UPDATE 가 영속성 컨텍스트를 비우지 않으면 먼저 읽어 둔 PENDING 이 그대로 돌아온다
			assertThat(reread).isEqualTo(OrderStatus.EXPIRED);
		}
	}

	@Nested
	@DisplayName("주문이 PENDING 이 아니면")
	class WhenNotPending {

		@ParameterizedTest(name = "[{index}] 지금 상태 {0}")
		@EnumSource(value = OrderStatus.class, mode = Mode.EXCLUDE, names = "PENDING")
		@DisplayName("PENDING 일 때만 EXPIRED 로 바꾸라고 하면 0행을 바꾸고 DB 의 상태는 그대로다")
		void changesNothing(OrderStatus current) {
			// given
			Long orderId = saveOrder(current);

			// when
			int changedRows = expireIfPendingInTransaction(orderId);

			// then
			assertThat(changedRows).as("바꾼 행 수").isZero();
			assertThat(statusInDatabase()).isEqualTo(current.name());
		}
	}

	/**
	 * 만료 서비스처럼 트랜잭션 안에서 "PENDING 일 때만 EXPIRED 로" 바꾸고 바꾼 행 수를 돌려준다. 이 UPDATE 는 @Modifying 이라
	 * 트랜잭션 밖에서 부르면 TransactionRequiredException 이 난다.
	 */
	private int expireIfPendingInTransaction(Long orderId) {
		Integer changedRows = new TransactionTemplate(transactionManager).execute(
			status -> orderRepository.updateStatusIf(orderId, OrderStatus.PENDING, OrderStatus.EXPIRED));
		return changedRows == null ? 0 : changedRows;
	}

	private Long saveOrder(OrderStatus status) {
		return orderRepository.save(TestOrders.order().merchantUid(merchantUid).userId(ANY_USER_ID)
			.subCategoryId(ANY_SUB_CATEGORY_ID).price(PRICE).buyer("상태변경", "status_update_" + runId + "@example.com")
			.inStatus(status)).getId();
	}

	private String statusInDatabase() {
		return jdbcTemplate.queryForObject("SELECT status FROM orders WHERE merchant_uid = ?", String.class,
			merchantUid);
	}
}
