package com.mansereok.server.domain.payment.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PaymentStatusTest {

	@ParameterizedTest(name = "{0} → {1}")
	@CsvSource({
		"PAID, PAID",
		"READY, READY",
		"PAY_PENDING, READY",
		"VIRTUAL_ACCOUNT_ISSUED, VIRTUAL_ACCOUNT_ISSUED",
		"FAILED, FAILED",
		"CANCELLED, CANCELLED",
		"PARTIAL_CANCELLED, CANCELLED",
	})
	@DisplayName("포트원 V2 상태 문자열 7가지가 기대한 상수로 매핑된다")
	void fromPortOneStatus_knownValues(String raw, PaymentStatus expected) {
		assertThat(PaymentStatus.fromPortOneStatus(raw)).contains(expected);
	}

	@ParameterizedTest
	@ValueSource(strings = {"paid", "Paid", "PAID", " paid ", "pay_pending", "Partial_Cancelled"})
	@DisplayName("대소문자와 앞뒤 공백을 무시하고 매핑한다")
	void fromPortOneStatus_caseInsensitive(String raw) {
		assertThat(PaymentStatus.fromPortOneStatus(raw)).isPresent();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "garbage", "SOMETHING_NEW", "PAI D"})
	@DisplayName("null, 빈 값, 모르는 값은 FAILED 로 접지 않고 빈 Optional 을 돌려준다")
	void fromPortOneStatus_unknown_returnsEmpty(String raw) {
		Optional<PaymentStatus> result = PaymentStatus.fromPortOneStatus(raw);

		assertThat(result).isEmpty();
		assertThat(result).isNotEqualTo(Optional.of(PaymentStatus.FAILED));
	}
}
