package com.mansereok.server.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.RefreshToken;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import com.mansereok.server.global.config.JwtProperties;
import com.mansereok.server.global.config.RefreshTokenProperties;
import com.mansereok.server.global.exception.InvalidRefreshTokenException;
import com.mansereok.server.support.fixture.RefreshTokenFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RefreshTokenService 가 조건부 UPDATE 의 결과(1 또는 0)와 잠가 읽은 토큰의 상태에 따라 새 토큰을 넣을지, 어떤 사유로 거절할지를
 * 확인한다. 조건부 UPDATE 가 몇 행을 고치는지, 겹친 요청이 서로를 기다리는지는 DB 가 지키는 규칙이라 RefreshTokenMySqlTest 가 본다.
 *
 * <p>리포지토리 목은 돌려줄 값만 정한다. 조회 인자를 정확한 값으로 스텁해 두므로 다른 시각이나 다른 토큰으로 조회하면 strict stubs 가
 * 테스트를 실패시킨다. 새 토큰을 넣는 save 와 토큰을 지우는 deleteByUser 는 상태를 바꾸는 명령이라 호출 여부를 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

	// 2026-09-26 14:45 (서울)
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-26T05:45:00Z"),
		ZoneId.of("Asia/Seoul"));
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 14, 45);
	private static final String PRESENTED = "presented-token";
	private static final Long MEMBER_ID = 7L;

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	private RefreshTokenService refreshTokenService;
	private User member;

	@BeforeEach
	void setUp() {
		// 리프레시 토큰 수명 30일, 쓴 토큰 재발급 유예 10초
		JwtProperties jwtProperties = new JwtProperties("refresh-token-service-test-secret-0123456789", 1_800_000L,
			2_592_000_000L, "mansereok");
		refreshTokenService = new RefreshTokenService(refreshTokenRepository, jwtProperties,
			new RefreshTokenProperties(Duration.ofSeconds(10)), FIXED_CLOCK);
		member = User.create("member@example.com", "회원", "encoded-password", "member@example.com",
			LocalDate.of(1990, 1, 1), Gender.FEMALE, true, true, false);
		member.setId(MEMBER_ID);
	}

	@Nested
	@DisplayName("처음 쓰는 토큰이면(조건부 UPDATE 가 1)")
	class WhenTokenIsUsedForTheFirstTime {

		@Test
		@DisplayName("그 회원에게 30일 동안 쓸 새 토큰을 하나 넣어 돌려주고, 회원의 다른 토큰은 지우지 않는다")
		void issuesOneNewTokenWithoutDeletingOtherTokens() {
			// given
			given(refreshTokenRepository.markUsedIfUsable(PRESENTED, NOW)).willReturn(1);
			given(refreshTokenRepository.findWithUserByToken(PRESENTED))
				.willReturn(Optional.of(RefreshTokenFixture.unusedTokenOf(member).build()));
			givenSaveReturnsItsArgument();

			// when
			RotatedRefreshToken rotated = refreshTokenService.rotate(PRESENTED);

			// then
			RefreshToken saved = savedToken();
			assertThat(rotated.user()).isSameAs(member);
			assertThat(rotated.token()).isEqualTo(saved.getToken()).isNotEqualTo(PRESENTED);
			assertThat(saved.getUser()).isSameAs(member);
			assertThat(saved.getCreatedAt()).isEqualTo(NOW);
			assertThat(saved.getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 10, 26, 14, 45));
			then(refreshTokenRepository).should(never()).deleteByUser(any());
		}
	}

	@Nested
	@DisplayName("다른 요청이 이미 쓴 토큰이면(조건부 UPDATE 가 0)")
	class WhenTokenWasAlreadyUsed {

		@ParameterizedTest(name = "[{index}] 쓴 뒤 {0} 지남")
		@DisplayName("쓴 지 유예 시간(10초)이 지나지 않았으면 같은 쿠키로 거의 동시에 온 요청으로 보고 새 토큰을 한 번 더 넣는다")
		@ValueSource(strings = {"PT0S", "PT9.999S", "PT10S"})
		void issuesAnotherTokenWithinGrace(String elapsedSinceUse) {
			// given
			RefreshToken usedWithinGrace = RefreshTokenFixture.unusedTokenOf(member)
				.usedAt(NOW.minus(Duration.parse(elapsedSinceUse))).build();
			givenLockedTokenAfterUpdateFailed(usedWithinGrace);
			given(refreshTokenRepository.findWithUserByToken(PRESENTED)).willReturn(Optional.of(usedWithinGrace));
			givenSaveReturnsItsArgument();

			// when
			RotatedRefreshToken rotated = refreshTokenService.rotate(PRESENTED);

			// then
			RefreshToken saved = savedToken();
			assertThat(rotated.token()).isEqualTo(saved.getToken());
			assertThat(rotated.user()).isSameAs(member);
			assertThat(saved.getUser()).isSameAs(member);
		}

		@ParameterizedTest(name = "[{index}] 쓴 뒤 {0} 지남")
		@DisplayName("쓴 지 유예 시간(10초)이 지났으면 이미 사용한 토큰이라 거절하고 새 토큰을 넣지 않는다")
		@ValueSource(strings = {"PT10.001S", "PT11S", "PT1H"})
		void rejectsReuseAfterGrace(String elapsedSinceUse) {
			// given
			givenLockedTokenAfterUpdateFailed(RefreshTokenFixture.unusedTokenOf(member)
				.usedAt(NOW.minus(Duration.parse(elapsedSinceUse))).build());

			// when & then
			assertThatThrownBy(() -> refreshTokenService.rotate(PRESENTED))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage("이미 사용한 리프레시 토큰입니다. 다시 로그인해주세요.");
			then(refreshTokenRepository).should(never()).save(any());
		}
	}

	@Nested
	@DisplayName("쓸 수 없는 토큰이면(조건부 UPDATE 가 0)")
	class WhenTokenCannotBeUsed {

		@Test
		@DisplayName("로그아웃으로 폐기된 토큰은 쓴 지 유예 시간 안이어도 폐기 사유로 거절하고 새 토큰을 넣지 않는다")
		void rejectsRevokedTokenEvenWithinGrace() {
			// given
			givenLockedTokenAfterUpdateFailed(RefreshTokenFixture.unusedTokenOf(member)
				.usedAt(NOW.minusSeconds(1)).revoked().build());

			// when & then
			assertThatThrownBy(() -> refreshTokenService.rotate(PRESENTED))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage("로그아웃했거나 폐기된 리프레시 토큰입니다. 다시 로그인해주세요.");
			then(refreshTokenRepository).should(never()).save(any());
		}

		@ParameterizedTest(name = "[{index}] 만료 시각 {0}")
		@DisplayName("만료 시각이 지금이거나 지났으면 만료 사유로 거절하고 새 토큰을 넣지 않는다")
		@ValueSource(strings = {"2026-09-26T14:45:00", "2026-09-26T14:44:59"})
		void rejectsExpiredToken(String expiresAt) {
			// given
			givenLockedTokenAfterUpdateFailed(RefreshTokenFixture.unusedTokenOf(member)
				.expiresAt(LocalDateTime.parse(expiresAt)).build());

			// when & then
			assertThatThrownBy(() -> refreshTokenService.rotate(PRESENTED))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage("만료된 리프레시 토큰입니다. 다시 로그인해주세요.");
			then(refreshTokenRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("DB 에 없는 토큰은 유효하지 않은 토큰으로 거절한다")
		void rejectsUnknownToken() {
			// given
			given(refreshTokenRepository.markUsedIfUsable(PRESENTED, NOW)).willReturn(0);
			given(refreshTokenRepository.findByTokenForUpdate(PRESENTED)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> refreshTokenService.rotate(PRESENTED))
				.isInstanceOf(InvalidRefreshTokenException.class)
				.hasMessage("유효하지 않은 리프레시 토큰입니다.");
			then(refreshTokenRepository).should(never()).save(any());
		}
	}

	@Nested
	@DisplayName("로그인하면")
	class WhenMemberLogsIn {

		@Test
		@DisplayName("같은 회원이 두 기기에서 로그인해도 토큰을 하나씩 넣기만 하고, 먼저 받은 기기의 토큰을 지우거나 폐기하지 않는다")
		void keepsTokenOfOtherDevice() {
			// given
			givenSaveReturnsItsArgument();

			// when
			String phoneToken = refreshTokenService.issue(member);
			String pcToken = refreshTokenService.issue(member);

			// then
			ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
			then(refreshTokenRepository).should(times(2)).save(saved.capture());
			assertThat(saved.getAllValues()).extracting(RefreshToken::getToken).containsExactly(phoneToken, pcToken);
			assertThat(phoneToken).isNotEqualTo(pcToken);
			then(refreshTokenRepository).should(never()).deleteByUser(any());
			then(refreshTokenRepository).should(never()).revokeAllUserTokens(any());
		}
	}

	@Nested
	@DisplayName("로그아웃하면")
	class WhenMemberLogsOut {

		@Test
		@DisplayName("아직 쓰지 않은 토큰이면 그 토큰 하나만 UPDATE 로 폐기한다")
		void revokesOnlyPresentedUnusedToken() {
			// given
			given(refreshTokenRepository.revokeByToken(PRESENTED)).willReturn(1);
			given(refreshTokenRepository.findByToken(PRESENTED))
				.willReturn(Optional.of(RefreshTokenFixture.unusedTokenOf(member).revoked().build()));

			// when
			refreshTokenService.revoke(PRESENTED);

			// then
			then(refreshTokenRepository).should(never()).revokeByIds(any());
		}

		@Test
		@DisplayName("이미 새 토큰으로 바뀐 토큰이면, 바뀐 시각부터 유예 시간(10초) 안에 그 회원에게 만든 토큰도 함께 폐기한다")
		void alsoRevokesTokensIssuedWithinGraceAfterUse() {
			// given
			LocalDateTime usedAt = LocalDateTime.of(2026, 9, 26, 14, 44, 57);
			given(refreshTokenRepository.revokeByToken(PRESENTED)).willReturn(1);
			given(refreshTokenRepository.findByToken(PRESENTED))
				.willReturn(Optional.of(RefreshTokenFixture.unusedTokenOf(member).usedAt(usedAt).revoked().build()));
			given(refreshTokenRepository.findUnrevokedIdsCreatedBetween(MEMBER_ID, usedAt,
				LocalDateTime.of(2026, 9, 26, 14, 45, 7))).willReturn(List.of(21L, 22L));

			// when
			refreshTokenService.revoke(PRESENTED);

			// then
			then(refreshTokenRepository).should().revokeByIds(List.of(21L, 22L));
		}

		@Test
		@DisplayName("DB 에 없는 토큰이면 더 읽거나 폐기하지 않는다")
		void doesNothingMoreForUnknownToken() {
			// given
			given(refreshTokenRepository.revokeByToken(PRESENTED)).willReturn(0);

			// when
			refreshTokenService.revoke(PRESENTED);

			// then
			then(refreshTokenRepository).should(never()).revokeByIds(any());
		}
	}

	/**
	 * 조건부 UPDATE 가 0 이고, 잠가 다시 읽은 토큰이 locked 인 상황을 스텁한다.
	 */
	private void givenLockedTokenAfterUpdateFailed(RefreshToken locked) {
		given(refreshTokenRepository.markUsedIfUsable(PRESENTED, NOW)).willReturn(0);
		given(refreshTokenRepository.findByTokenForUpdate(PRESENTED)).willReturn(Optional.of(locked));
	}

	private void givenSaveReturnsItsArgument() {
		given(refreshTokenRepository.save(any(RefreshToken.class))).willAnswer(invocation -> invocation.getArgument(0));
	}

	/**
	 * 새 토큰을 한 번만 넣었는지 확인하고, 넣은 토큰을 돌려준다.
	 */
	private RefreshToken savedToken() {
		ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
		then(refreshTokenRepository).should(times(1)).save(saved.capture());
		return saved.getValue();
	}
}
