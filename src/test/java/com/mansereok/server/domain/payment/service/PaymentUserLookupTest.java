package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentUserLookupTest {

	private static final String USERNAME = "testUser";

	@Mock
	private UserRepository userRepository;

	private PaymentUserLookup paymentUserLookup;

	@BeforeEach
	void setUp() {
		paymentUserLookup = new PaymentUserLookup(userRepository);
	}

	@Test
	@DisplayName("username 의 사용자가 있으면 그 사용자를 돌려준다")
	void returnsUserWhenFound() {
		// given
		User user = User.create(USERNAME, "김태우", "password", "taewoo@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, true);
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(user));

		// when
		User found = paymentUserLookup.getByUsername(USERNAME);

		// then
		assertThat(found).isSameAs(user);
	}

	@Test
	@DisplayName("사용자가 없으면(탈퇴 직후 남은 토큰 등) '사용자를 찾을 수 없습니다.' PaymentException 을 던진다")
	void throwsPaymentExceptionWhenMissing() {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> paymentUserLookup.getByUsername(USERNAME))
			.isInstanceOf(PaymentException.class)
			.hasMessage("사용자를 찾을 수 없습니다.");
	}
}
