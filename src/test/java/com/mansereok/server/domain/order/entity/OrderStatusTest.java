package com.mansereok.server.domain.order.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class OrderStatusTest {

	/**
	 * 허용 전이 전수. 여기 없는 조합은 모두 불허다.
	 */
	private static final Set<String> ALLOWED = Set.of(
		"PENDING->PAID",
		"PENDING->FAILED",
		"PENDING->EXPIRED",
		"VIRTUAL_ACCOUNT_ISSUED->PAID",
		"VIRTUAL_ACCOUNT_ISSUED->FAILED",
		"VIRTUAL_ACCOUNT_ISSUED->EXPIRED",
		"EXPIRED->PAID",
		"PAID->CANCELLED"
	);

	@ParameterizedTest(name = "{0} -> {1} 는 허용된다")
	@DisplayName("허용 전이 표")
	@CsvSource({
		"PENDING, PAID",
		"PENDING, FAILED",
		"PENDING, EXPIRED",
		"VIRTUAL_ACCOUNT_ISSUED, PAID",
		"VIRTUAL_ACCOUNT_ISSUED, FAILED",
		"VIRTUAL_ACCOUNT_ISSUED, EXPIRED",
		"EXPIRED, PAID",
		"PAID, CANCELLED"
	})
	void allowedTransitions(OrderStatus from, OrderStatus to) {
		assertThat(from.canTransitionTo(to)).isTrue();
	}

	@ParameterizedTest(name = "{0} -> {1} 는 불허된다")
	@DisplayName("불허 전이 대표 케이스")
	@CsvSource({
		"PENDING, CANCELLED",
		"PENDING, VIRTUAL_ACCOUNT_ISSUED",
		"PENDING, PENDING",
		"PAID, PAID",
		"PAID, EXPIRED",
		"PAID, FAILED",
		"PAID, PENDING",
		"CANCELLED, PAID",
		"CANCELLED, CANCELLED",
		"FAILED, PAID",
		"FAILED, FAILED",
		"FAILED, EXPIRED",
		"EXPIRED, EXPIRED",
		"EXPIRED, FAILED",
		"EXPIRED, CANCELLED",
		"VIRTUAL_ACCOUNT_ISSUED, CANCELLED",
		"VIRTUAL_ACCOUNT_ISSUED, PENDING"
	})
	void disallowedTransitions(OrderStatus from, OrderStatus to) {
		assertThat(from.canTransitionTo(to)).isFalse();
	}

	@ParameterizedTest(name = "{0} 에서 나가는 6개 전이 전부가 표와 일치한다")
	@DisplayName("6x6 전이 표 전수 검증")
	@EnumSource(OrderStatus.class)
	void fullMatrixMatchesTable(OrderStatus from) {
		for (OrderStatus to : OrderStatus.values()) {
			boolean expected = ALLOWED.contains(from.name() + "->" + to.name());
			assertThat(from.canTransitionTo(to))
				.as("%s -> %s", from, to)
				.isEqualTo(expected);
		}
	}

	@ParameterizedTest(name = "{0} 에서는 어디로도 갈 수 없다")
	@DisplayName("CANCELLED, FAILED 는 종단 상태다")
	@EnumSource(value = OrderStatus.class, names = {"CANCELLED", "FAILED"})
	void terminalStatesHaveNoOutgoingTransition(OrderStatus from) {
		for (OrderStatus to : EnumSet.allOf(OrderStatus.class)) {
			assertThat(from.canTransitionTo(to)).as("%s -> %s", from, to).isFalse();
		}
	}
}
