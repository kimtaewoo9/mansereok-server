package com.mansereok.server.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.coupon.repository.CouponRepository;
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
import com.mansereok.server.support.fixture.CouponFixture;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 한 장뿐인 쿠폰을 여러 요청이 동시에 써도 한 번만 쓰이는지 실제 MySQL 로 확인한다.
 *
 * <p>같은 사용자가 탭 두 개에서 결제 버튼을 누르거나 버튼을 빠르게 여러 번 누르면, 같은 쿠폰을 담은 주문 만들기가 한꺼번에 들어온다.
 * 주문 만들기는 쿠폰 행을 잠가(CouponRepository.findByIdWithLock) 읽은 뒤 쓰지 않은 쿠폰인지 확인하고, 주문을 저장하고, 쿠폰을
 * 사용 처리한다. 이 잠금이 없으면 요청들이 모두 "쓰지 않은 쿠폰" 을 읽어 할인 주문이 여러 건 생긴다. 쿠폰 사용 처리(useCoupon)도
 * 따로 불릴 때를 위해 스스로 같은 잠금을 건다. 어느 요청이 먼저 잠금을 잡을지는 매번 달라서 여러 번 되풀이한다.
 *
 * <p>주문 만들기는 실제 서비스를 부르고, 밖으로 나가는 호출만 목이다. DB 에 남은 사실은 JPA 캐시를 거치지 않고 SQL 로 센다. 데이터는
 * 실행마다 다른 키(runId)로 만들고 그 키로 만든 사용자의 행만 지운다.
 */
class CouponUseConcurrencyTest extends PaymentMySqlTest {

	// HikariCP 기본 최대 커넥션 수(10)를 넘기지 않는다. 넘기면 잠금이 아니라 커넥션을 기다리는 시간을 재게 된다.
	private static final int REQUEST_COUNT = 10;
	private static final int PRICE = 10000;
	private static final int COUPON_DISCOUNT = 1000;

	@Autowired
	private PaymentOrderService paymentOrderService;
	@Autowired
	private CouponService couponService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private SubCategoryRepository subCategoryRepository;
	@Autowired
	private CouponRepository couponRepository;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다. @RepeatedTest 는 되풀이마다 새 인스턴스를 만든다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String username = "coupon_race_" + runId;

	private Long userId;
	private Long subCategoryId;
	private Long couponId;

	@BeforeEach
	void createBuyerProductAndUnusedCoupon() {
		userId = userRepository.save(User.create(username, "쿠폰동시사용", "password",
			username + "@example.com", LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false))
			.getId();
		subCategoryId = subCategoryRepository.save(SubCategoryFixture.paidProduct().withoutId()
			.title("쿠폰 동시 사용 테스트 상품 " + runId).price(PRICE).build()).getId();
		couponId = couponRepository.save(CouponFixture.fixedAmount(COUPON_DISCOUNT).withoutId()
			.userId(userId).name("동시 사용 확인 쿠폰 " + runId).build()).getId();
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM coupons WHERE user_id = ?", userId);
		subCategoryRepository.deleteById(subCategoryId);
		userRepository.deleteById(userId);
	}

	@RepeatedTest(value = 5, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("같은 쿠폰으로 주문 만들기가 동시에 10번 오면 9,000원 PENDING 주문 1건만 생기고 쿠폰은 사용 상태가 되며, 나머지 9번은 '이미 사용한 쿠폰입니다.' 로 거절된다")
	void tenOrdersWithSameCouponAtOnce() {
		// given
		OrderCreateRequest request = orderRequestWithCoupon();

		// when
		List<CallResult<OrderCreateResponse>> results = ConcurrentCalls.runAtTheSameTime(REQUEST_COUNT,
			() -> paymentOrderService.createOrder(username, request));

		// then: 요청마다 결과를 확인한다
		String resultsPerRequest = ConcurrentCalls.describe(results);
		assertThat(results).filteredOn(CallResult::succeeded)
			.as("주문을 만든 요청. %s", resultsPerRequest)
			.singleElement()
			.satisfies(result -> assertThat(result.value().getAmount()).as("쿠폰 할인가").isEqualTo(9000));
		assertThat(results).filteredOn(result -> !result.succeeded())
			.as("거절된 요청. %s", resultsPerRequest)
			.hasSize(9)
			.allSatisfy(result -> assertThat(result.error())
				.isExactlyInstanceOf(PaymentException.class)
				.hasMessage("이미 사용한 쿠폰입니다."));

		// then: DB 에 남은 사실은 JPA 캐시를 거치지 않고 SQL 로 센다
		assertThat(jdbcTemplate.queryForList("SELECT status FROM orders WHERE coupon_id = ?", String.class,
			couponId))
			.as("이 쿠폰을 쥔 주문의 상태. %s", resultsPerRequest)
			.containsExactly("PENDING");
		assertThat(couponIsUsed()).as("쿠폰 사용 여부. %s", resultsPerRequest).isTrue();
	}

	@RepeatedTest(value = 5, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("같은 쿠폰을 두 요청이 동시에 사용 처리하면 한 요청만 성공하고 다른 요청은 '이미 사용된 쿠폰입니다.' 로 거절되며, 쿠폰은 사용 상태로 남는다")
	void twoUseCouponCallsAtOnce() {
		// when
		List<CallResult<Void>> results = ConcurrentCalls.runAtTheSameTime(2, () -> {
			couponService.useCoupon(couponId);
			return null;
		});

		// then
		String resultsPerRequest = ConcurrentCalls.describe(results);
		assertThat(results).filteredOn(CallResult::succeeded)
			.as("사용 처리한 요청. %s", resultsPerRequest)
			.hasSize(1);
		assertThat(results).filteredOn(result -> !result.succeeded())
			.as("거절된 요청. %s", resultsPerRequest)
			.singleElement()
			.satisfies(result -> assertThat(result.error())
				.isExactlyInstanceOf(PaymentException.class)
				.hasMessage("이미 사용된 쿠폰입니다."));
		assertThat(couponIsUsed()).as("쿠폰 사용 여부. %s", resultsPerRequest).isTrue();
	}

	private OrderCreateRequest orderRequestWithCoupon() {
		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(subCategoryId);
		request.setCouponId(couponId);
		return request;
	}

	private boolean couponIsUsed() {
		return Boolean.TRUE.equals(
			jdbcTemplate.queryForObject("SELECT is_used FROM coupons WHERE id = ?", Boolean.class, couponId));
	}
}
