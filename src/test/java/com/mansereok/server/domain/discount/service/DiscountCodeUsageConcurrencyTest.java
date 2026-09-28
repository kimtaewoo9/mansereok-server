package com.mansereok.server.domain.discount.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.discount.repository.DiscountCodeRepository;
import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import com.mansereok.server.domain.order.dto.response.OrderCreateResponse;
import com.mansereok.server.domain.payment.service.PaymentOrderService;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.DiscountCodeFixture;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 선착순 할인 코드에 사람이 몰려도 정한 횟수보다 많이 쓰이지 않는지 실제 MySQL 로 확인한다.
 *
 * <p>주문 만들기와 무료 받기(100% 할인 코드)는 할인 코드 행을 잠가(DiscountCodeRepository.findByCodeForUpdate) 읽은 뒤 남은 횟수를
 * 확인하고, 주문을 저장하고, 사용 횟수를 1 올린다. 이 잠금이 없으면 요청들이 같은 사용 횟수를 읽고 모두 확인을 통과해 정한 횟수보다
 * 많은 할인 주문이 생기고, 사용 횟수는 서로 덮어써 실제보다 작게 남는다. 무료 받기에서는 0원 결제 완료가 그만큼 더 생긴다. 어느
 * 요청이 먼저 잠금을 잡을지는 매번 달라서 여러 번 되풀이한다.
 *
 * <p>주문 만들기와 무료 받기는 실제 서비스를 부르고, 밖으로 나가는 호출만 목이다. DB 에 남은 사실은 JPA 캐시를 거치지 않고 SQL 로
 * 센다. 데이터는 실행마다 다른 키(runId)로 만들고 그 키로 만든 사용자와 코드의 행만 지운다.
 */
class DiscountCodeUsageConcurrencyTest extends PaymentMySqlTest {

	// 동시 요청 수는 HikariCP 기본 최대 커넥션 수(10)를 넘기지 않는다. 넘기면 잠금이 아니라 커넥션을 기다리는 시간을 재게 된다.
	// 3번 쓸 수 있는 할인 코드에 몰리는 사용자 수
	private static final int THREE_USE_CODE_BUYER_COUNT = 10;
	// 1번 쓸 수 있는 100% 할인 코드로 무료 받기에 몰리는 사용자 수
	private static final int FREE_CODE_BUYER_COUNT = 5;
	private static final int PRICE = 10000;

	@Autowired
	private PaymentOrderService paymentOrderService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private SubCategoryRepository subCategoryRepository;
	@Autowired
	private DiscountCodeRepository discountCodeRepository;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다. @RepeatedTest 는 되풀이마다 새 인스턴스를 만든다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String discountCode = "RACE-" + runId;
	private final String freeCode = "RACE-FREE-" + runId;

	// 테스트마다 필요한 수만큼 만든 사용자. 뒤 정리가 이 id 로만 지운다.
	private final List<Long> userIds = new ArrayList<>();
	private Long subCategoryId;

	@BeforeEach
	void createProduct() {
		subCategoryId = subCategoryRepository.save(SubCategoryFixture.paidProduct().withoutId()
			.title("할인 코드 동시 사용 테스트 상품 " + runId).price(PRICE).build()).getId();
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		for (Long userId : userIds) {
			String paymentsOfThisUser = "SELECT id FROM payments WHERE user_id = ?";
			jdbcTemplate.update("DELETE FROM results WHERE payment_id IN (" + paymentsOfThisUser + ")", userId);
			jdbcTemplate.update("DELETE FROM compatibility_results WHERE payment_id IN (" + paymentsOfThisUser + ")",
				userId);
			jdbcTemplate.update("DELETE FROM payments WHERE user_id = ?", userId);
			jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", userId);
			userRepository.deleteById(userId);
		}
		jdbcTemplate.update("DELETE FROM discount_codes WHERE code IN (?, ?)", discountCode, freeCode);
		subCategoryRepository.deleteById(subCategoryId);
	}

	@RepeatedTest(value = 5, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("선착순 3번 쓸 수 있는 할인 코드로 서로 다른 사용자 10명이 동시에 주문을 만들면 3명만 9,000원 주문을 만들고 7명은 '선착순 마감된 코드입니다.' 로 거절되며, 사용 횟수는 3 이다")
	void tenBuyersRaceForThreeUses() {
		// given
		List<String> buyers = createBuyers(THREE_USE_CODE_BUYER_COUNT);
		discountCodeRepository.save(DiscountCodeFixture.fixedAmount(1000).withoutId()
			.code(discountCode).maxUses(3).build());

		// when
		List<CallResult<OrderCreateResponse>> results = ConcurrentCalls.runAtTheSameTime(THREE_USE_CODE_BUYER_COUNT,
			index -> () -> paymentOrderService.createOrder(buyers.get(index), orderRequestWith(discountCode)));

		// then: 요청마다 결과를 확인한다
		String resultsPerRequest = ConcurrentCalls.describe(results);
		assertThat(results).filteredOn(CallResult::succeeded)
			.as("주문을 만든 요청. %s", resultsPerRequest)
			.hasSize(3)
			.allSatisfy(result -> assertThat(result.value().getAmount()).as("할인가").isEqualTo(9000));
		assertThat(results).filteredOn(result -> !result.succeeded())
			.as("거절된 요청. %s", resultsPerRequest)
			.hasSize(7)
			.allSatisfy(result -> assertThat(result.error())
				.isExactlyInstanceOf(PaymentException.class)
				.hasMessage("선착순 마감된 코드입니다."));

		// then: DB 에 남은 사실은 JPA 캐시를 거치지 않고 SQL 로 센다
		assertThat(currentUses(discountCode)).as("사용 횟수. %s", resultsPerRequest).isEqualTo(3);
		assertThat(jdbcTemplate.queryForList("SELECT status FROM orders WHERE applied_discount_code = ?",
			String.class, discountCode))
			.as("이 코드로 만든 주문의 상태. %s", resultsPerRequest)
			.containsExactly("PENDING", "PENDING", "PENDING");
	}

	@RepeatedTest(value = 5, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("1번만 쓸 수 있는 100% 할인 코드로 서로 다른 사용자 5명이 동시에 무료 받기를 하면 1명만 0원 주문을 받고 4명은 '선착순 마감된 코드입니다.' 로 거절되며, 0원 결제는 imp_uid 가 free_ 에 주문 번호를 붙인 1건이다")
	void fiveBuyersRaceForOneFreeCode() {
		// given
		List<String> buyers = createBuyers(FREE_CODE_BUYER_COUNT);
		discountCodeRepository.save(DiscountCodeFixture.percentage(100).withoutId()
			.code(freeCode).maxUses(1).build());

		// when
		List<CallResult<OrderCreateResponse>> results = ConcurrentCalls.runAtTheSameTime(FREE_CODE_BUYER_COUNT,
			index -> () -> paymentOrderService.redeemFreeProduct(buyers.get(index), orderRequestWith(freeCode)));

		// then
		String resultsPerRequest = ConcurrentCalls.describe(results);
		assertThat(results).filteredOn(CallResult::succeeded)
			.as("무료로 받은 요청. %s", resultsPerRequest)
			.singleElement()
			.satisfies(result -> assertThat(result.value().getAmount()).as("결제 금액").isZero());
		assertThat(results).filteredOn(result -> !result.succeeded())
			.as("거절된 요청. %s", resultsPerRequest)
			.hasSize(4)
			.allSatisfy(result -> assertThat(result.error())
				.isExactlyInstanceOf(PaymentException.class)
				.hasMessage("선착순 마감된 코드입니다."));

		// then: 0원 결제의 imp_uid 는 포트원 결제 번호 대신 "free_" 에 주문 번호(merchant_uid)를 붙인 값이다
		assertThat(currentUses(freeCode)).as("사용 횟수. %s", resultsPerRequest).isEqualTo(1);
		assertThat(jdbcTemplate.queryForList("SELECT p.imp_uid, p.merchant_uid, p.amount, o.status FROM payments p "
			+ "JOIN orders o ON o.merchant_uid = p.merchant_uid WHERE o.applied_discount_code = ?", freeCode))
			.as("이 코드로 받은 결제와 그 주문. %s", resultsPerRequest)
			.singleElement()
			.satisfies(row -> {
				assertThat(row.get("imp_uid")).isEqualTo("free_" + row.get("merchant_uid"));
				assertThat(((Number) row.get("amount")).longValue()).isZero();
				assertThat(row.get("status")).isEqualTo("PAID");
			});
	}

	/** 서로 다른 사용자를 count 명 저장하고 로그인 아이디를 돌려준다. */
	private List<String> createBuyers(int count) {
		List<String> usernames = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			String username = "code_race_" + runId + "_" + index;
			usernames.add(username);
			userIds.add(userRepository.save(User.create(username, "코드동시사용", "password",
				username + "@example.com", LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false)).getId());
		}
		return usernames;
	}

	private OrderCreateRequest orderRequestWith(String code) {
		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(subCategoryId);
		request.setDiscountCode(code);
		return request;
	}

	private Integer currentUses(String code) {
		return jdbcTemplate.queryForObject("SELECT current_uses FROM discount_codes WHERE code = ?", Integer.class,
			code);
	}
}
