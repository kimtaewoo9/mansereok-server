package com.mansereok.server.domain.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 재설정 토큰이 받은 "지금" 시각으로 만료를 판단하고, 다시 발급하면 값과 만료 시각이 바뀌는지 확인한다.
 *
 * <p>토큰은 시스템 시계를 읽지 않고 호출하는 쪽(UserService 가 주입받은 Clock)이 넘긴 시각만 쓴다. 그래서 여기서는 시각을 값으로
 * 넘겨 시간이 흐르기를 기다리지 않는다.
 */
class PasswordResetTokenTest {

	private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 9, 26, 14, 45);

	@ParameterizedTest(name = "[{index}] 14:45 에 만든 토큰을 {0} 에 보면 만료 {1}")
	@CsvSource(textBlock = """
		# 지금 시각,                 만료 여부
		2026-09-26T14:45:00,          false
		2026-09-26T14:59:00,          false
		2026-09-26T14:59:59.999999,   false
		# 만료 시각 15:00 과 같으면 만료다. 토큰을 쓰는 DELETE 도 '만료 시각 > 지금' 인 행만 지운다.
		2026-09-26T15:00:00,          true
		2026-09-26T15:01:00,          true
		""")
	@DisplayName("만든 시각부터 15분이 되는 순간부터 만료로 본다")
	void expiresFifteenMinutesAfterIssue(LocalDateTime now, boolean expected) {
		// given
		PasswordResetToken token = new PasswordResetToken(member(), ISSUED_AT);

		// when
		boolean expired = token.isExpiredAt(now);

		// then
		assertThat(expired).isEqualTo(expected);
	}

	@Nested
	@DisplayName("같은 사용자가 다시 요청해 다시 발급하면")
	class WhenReissued {

		private static final LocalDateTime REISSUED_AT = LocalDateTime.of(2026, 9, 26, 15, 10);

		@Test
		@DisplayName("토큰 값이 바뀌어 앞서 보낸 링크의 값과 다르다")
		void changesTokenValue() {
			// given
			PasswordResetToken token = new PasswordResetToken(member(), ISSUED_AT);
			String firstValue = token.getToken();

			// when
			token.reissue(REISSUED_AT);

			// then
			assertThat(token.getToken()).isNotEqualTo(firstValue);
		}

		@Test
		@DisplayName("만료 시각이 다시 발급한 시각부터 15분 뒤로 옮겨진다")
		void movesExpiryFifteenMinutesAfterReissue() {
			// given: 15:00 에 이미 만료된 토큰
			PasswordResetToken token = new PasswordResetToken(member(), ISSUED_AT);

			// when
			token.reissue(REISSUED_AT);

			// then
			assertThat(token.getExpiryDate()).isEqualTo(LocalDateTime.of(2026, 9, 26, 15, 25));
			assertThat(token.isExpiredAt(LocalDateTime.of(2026, 9, 26, 15, 24))).isFalse();
		}
	}

	private static User member() {
		return User.create("member@example.com", "재설정회원", "encoded-password", "member@example.com",
			LocalDate.of(1990, 1, 1), Gender.FEMALE, true, true, false);
	}
}
