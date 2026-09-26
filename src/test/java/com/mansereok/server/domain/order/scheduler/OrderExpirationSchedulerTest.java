package com.mansereok.server.domain.order.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.order.service.OrderExpirationService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 만료 스케줄러가 지키는 약속 세 가지를 검증한다.
 *
 * <ol>
 *   <li>만료 기준 시각은 주입된 Clock 의 "지금 - 30분" 이다.</li>
 *   <li>한 건이 실패해도 예외를 밖으로 던지지 않고 나머지 건을 계속 처리한다.</li>
 *   <li>30분마다 한 번 돈다. 만료 기준과 같은 30분이지만 다른 값이라 따로 확인한다.</li>
 * </ol>
 *
 * <p>OrderRepository 는 조회 결과를 돌려주는 스텁으로만 쓰고 호출 여부는 verify 하지 않는다. 기준 시각을 정확한 값으로
 * 스텁해 두면, 코드가 다른 시각으로 조회할 때 MockitoExtension 의 strict stubs 가 PotentialStubbingProblem 으로
 * 테스트를 실패시킨다. 조회를 verify 로 한 번 더 확인할 필요가 없다.
 */
@ExtendWith(MockitoExtension.class)
class OrderExpirationSchedulerTest {

	// 2026-09-21 09:00 (서울). 시간대를 명시해 개발자 PC 의 시간대에 결과가 흔들리지 않게 한다.
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-21T00:00:00Z"),
		ZoneId.of("Asia/Seoul"));
	private static final LocalDateTime EXPECTED_CUTOFF = LocalDateTime.of(2026, 9, 21, 8, 30);

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private OrderExpirationService orderExpirationService;

	private OrderExpirationScheduler scheduler;

	@BeforeEach
	void setUp() {
		scheduler = new OrderExpirationScheduler(orderRepository, orderExpirationService,
			FIXED_CLOCK);
	}

	@Nested
	@DisplayName("만료 대상 주문이 없으면")
	class WhenNothingToExpire {

		@Test
		@DisplayName("건별 만료 서비스를 한 번도 부르지 않는다")
		void doesNotCallExpirationService() {
			// given
			givenPendingOrdersCreatedBeforeCutoff();

			// when
			scheduler.expireStaleOrders();

			// then
			verifyNoInteractions(orderExpirationService);
		}
	}

	@Nested
	@DisplayName("만료 대상 주문이 여러 건이면")
	class WhenSeveralOrdersToExpire {

		@Test
		@DisplayName("Clock 기준 30분 전보다 먼저 만든 PENDING 주문을 한 건씩 만료시킨다")
		void expiresEachOrderOneByOne() {
			// given
			givenPendingOrdersCreatedBeforeCutoff(10L, 11L);
			given(orderExpirationService.expireIfStillPending(10L)).willReturn(true);
			given(orderExpirationService.expireIfStillPending(11L)).willReturn(true);

			// when
			scheduler.expireStaleOrders();

			// then
			then(orderExpirationService).should().expireIfStillPending(10L);
			then(orderExpirationService).should().expireIfStillPending(11L);
		}

		@Test
		@DisplayName("조회와 갱신 사이에 이미 다른 상태가 된 주문(false)은 건너뛰고 뒷 건을 계속 처리한다")
		void skipsOrderThatIsNoLongerPending() {
			// given
			givenPendingOrdersCreatedBeforeCutoff(30L, 31L);
			given(orderExpirationService.expireIfStillPending(30L)).willReturn(false);
			given(orderExpirationService.expireIfStillPending(31L)).willReturn(true);

			// when
			scheduler.expireStaleOrders();

			// then
			then(orderExpirationService).should().expireIfStillPending(31L);
		}

		@Test
		@DisplayName("앞 건이 예외로 실패해도 예외를 밖으로 던지지 않고 뒷 건을 계속 처리한다")
		void keepsGoingAfterOneFailure() {
			// given
			givenPendingOrdersCreatedBeforeCutoff(20L, 21L);
			willThrow(new IllegalStateException("DB 오류"))
				.given(orderExpirationService).expireIfStillPending(20L);
			given(orderExpirationService.expireIfStillPending(21L)).willReturn(true);

			// when & then
			assertThatCode(() -> scheduler.expireStaleOrders()).doesNotThrowAnyException();
			then(orderExpirationService).should().expireIfStillPending(21L);
		}
	}

	@Nested
	@DisplayName("만료 스캔 주기는")
	class ScanInterval {

		@Test
		@DisplayName("@Scheduled 의 fixedRate 와 timeUnit 을 합쳐 30분이다")
		void runsEveryThirtyMinutes() throws NoSuchMethodException {
			// when
			Scheduled scheduled = OrderExpirationScheduler.class.getMethod("expireStaleOrders")
				.getAnnotation(Scheduled.class);

			// then
			assertThat(Duration.of(scheduled.fixedRate(), scheduled.timeUnit().toChronoUnit()))
				.as("@Scheduled 의 fixedRate(%s) 와 timeUnit(%s)", scheduled.fixedRate(), scheduled.timeUnit())
				.isEqualTo(Duration.ofMinutes(30));
		}
	}

	/**
	 * 만료 대상 조회를 스텁한다. 기준 시각이 EXPECTED_CUTOFF 와 다르면 strict stubs 가 테스트를 실패시킨다.
	 */
	private void givenPendingOrdersCreatedBeforeCutoff(Long... orderIds) {
		given(orderRepository.findIdsByStatusAndCreatedAtBefore(OrderStatus.PENDING, EXPECTED_CUTOFF))
			.willReturn(List.of(orderIds));
	}
}
