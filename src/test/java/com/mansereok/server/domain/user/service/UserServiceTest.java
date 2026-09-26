package com.mansereok.server.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.interpret.dto.response.InterpretationResultResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseCompatibilityAnalysisResponse;
import com.mansereok.server.domain.interpret.dto.response.SajuHistoryResponseDto;
import com.mansereok.server.domain.interpret.dto.response.SajuHistoryResponseDto.ResultType;
import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.auth.PasswordResetTokenRepository;
import com.mansereok.server.domain.auth.entity.PasswordResetToken;
import com.mansereok.server.domain.auth.service.oauth.OauthProfile;
import com.mansereok.server.domain.user.dto.request.ProfileUpdateRequestDto;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.event.PasswordResetRequestedEvent;
import com.mansereok.server.domain.user.event.UserRegisteredEvent;
import com.mansereok.server.domain.user.event.UserWithdrawnEvent;
import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.DuplicateEmailException;
import com.mansereok.server.support.fixture.UserFixture;
import jakarta.persistence.EntityNotFoundException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * UserService 의 가입·탈퇴·프로필 규칙을 확인한다.
 *
 * <p>UserService 는 Discord·Slack 알림 서비스를 갖지 않는다. 가입·탈퇴 알림은 발행한 이벤트를 UserNotificationListener 가 커밋
 * 뒤에 받아 보내므로, 여기서는 이벤트가 한 번, 알맞은 값으로 발행되는지만 본다. 알림 내용은 UserNotificationListenerTest 가,
 * 커밋 뒤에만 나가는지는 WithdrawalMySqlTest 가 본다.
 *
 * <p>비밀번호 재설정도 같다. 재설정 메일은 발행한 이벤트를 PasswordResetMailListener 가 커밋 뒤에 받아 보내므로 여기서는 이벤트만
 * 본다. 사용자 행 잠금, 조건부 DELETE, 바뀐 컬럼만 쓰는 UPDATE 는 DB 가 지키는 규칙이라 PasswordResetMySqlTest 가 본다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
public class UserServiceTest {

	// 2026-09-26 14:45 (서울). 재설정 토큰의 만료 판단과 새 토큰의 만료 시각이 이 시계를 따른다.
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-26T05:45:00Z"),
		ZoneId.of("Asia/Seoul"));

	private UserService userService;

	@Mock
	private UserRepository userRepository;

	@Mock
	private ResultRepository resultRepository;

	@Mock
	private CompatibilityResultRepository compatibilityResultRepository;

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private PasswordResetTokenRepository passwordResetTokenRepository;
	@Mock
	private ReviewRepository reviewRepository;

	// UserService 생성자에 필요한 기타 Mock 객체들
	@Mock
	private PasswordEncoder passwordEncoder;
	@Mock
	private EmailService emailService;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	// UserService 가 이 목으로 진짜 TransactionTemplate 을 만든다. 트랜잭션을 열면 null 을 돌려주고 커밋·롤백은 아무것도 하지 않아,
	// 가입 코드는 트랜잭션 안에서처럼 그대로 돈다.
	@Mock
	private PlatformTransactionManager transactionManager;

	@BeforeEach
	void createUserService() {
		userService = new UserService(userRepository, resultRepository, compatibilityResultRepository,
			refreshTokenRepository, orderRepository, paymentRepository, passwordResetTokenRepository,
			passwordEncoder, emailService, reviewRepository, eventPublisher, transactionManager, FIXED_CLOCK);
	}

	@Test
	@DisplayName("회원 탈퇴 실패: 존재하지 않는 사용자인 경우 예외가 발생한다.")
	void deleteUser_UserNotFound() {
		// given
		String username = "unknownUser";
		given(userRepository.findByUsername(username)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> userService.deleteUser(username))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("사용자를 찾을 수 없습니다");
	}

	@Test
	@DisplayName("탈퇴 중 사용자 행을 잠그려는데 그사이 다른 요청이 먼저 탈퇴시켜 행이 없으면 404 가 되는 예외이고, 사용자를 지우지도 탈퇴 이벤트를 내지도 않는다")
	void deleteUser_MemberRowRemovedMeanwhile() {
		// given
		User member = User.create("gone@example.com", "탈퇴회원", "encoded-password", "gone@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false);
		UserFixture.withId(member, 8L);
		given(userRepository.findByUsername("gone@example.com")).willReturn(Optional.of(member));
		given(userRepository.findByIdForUpdate(8L)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> userService.deleteUser("gone@example.com"))
			.isInstanceOf(EntityNotFoundException.class)
			.hasMessage("사용자를 찾을 수 없습니다.");
		then(userRepository).should(never()).delete(any());
		verifyNoInteractions(eventPublisher);
	}

	@Nested
	@DisplayName("회원을 탈퇴시키면")
	class WhenWithdrawing {

		private static final String USERNAME = "leave@example.com";
		private static final Long USER_ID = 7L;

		private User member;

		@BeforeEach
		void givenMember() {
			member = User.create(USERNAME, "탈퇴회원", "encoded-password", USERNAME, LocalDate.of(1990, 1, 1),
				Gender.MALE, true, true, false);
			UserFixture.withId(member, USER_ID);
			given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(member));
			// 재설정 토큰을 지우기 전에 잠그는 사용자 행
			given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(member));
		}

		@Test
		@DisplayName("사용자 행을 지우기 전에 그 사용자를 가리키는 재설정 토큰·리프레시 토큰·사주 결과·궁합 결과·리뷰를 지운다")
		void deletesRowsPointingToUserBeforeUser() {
			// when
			userService.deleteUser(USERNAME);

			// then: 각 삭제가 사용자 삭제보다 먼저인지만 본다. 사용자를 가리키는 행끼리의 순서는 결과와 상관없어 묶지 않는다.
			InOrder resetTokenFirst = inOrder(passwordResetTokenRepository, userRepository);
			resetTokenFirst.verify(passwordResetTokenRepository).deleteAllByUserId(USER_ID);
			resetTokenFirst.verify(userRepository).delete(member);

			InOrder refreshTokenFirst = inOrder(refreshTokenRepository, userRepository);
			refreshTokenFirst.verify(refreshTokenRepository).deleteByUser(member);
			refreshTokenFirst.verify(userRepository).delete(member);

			InOrder resultFirst = inOrder(resultRepository, userRepository);
			resultFirst.verify(resultRepository).deleteAllByUserId(USER_ID);
			resultFirst.verify(userRepository).delete(member);

			InOrder compatibilityResultFirst = inOrder(compatibilityResultRepository, userRepository);
			compatibilityResultFirst.verify(compatibilityResultRepository).deleteAllByUserId(USER_ID);
			compatibilityResultFirst.verify(userRepository).delete(member);

			// 리뷰는 작성자 이름·이메일 사본을 들고 있다. 이 호출이 빠지면 탈퇴한 회원의 개인정보가 리뷰에 남는다.
			InOrder reviewFirst = inOrder(reviewRepository, userRepository);
			reviewFirst.verify(reviewRepository).deleteAllByUserId(USER_ID);
			reviewFirst.verify(userRepository).delete(member);
		}

		@Test
		@DisplayName("주문·결제 내역은 지우지 않고 사용자 연결만 끊는다")
		void detachesOrdersAndPayments() {
			// when
			userService.deleteUser(USERNAME);

			// then
			then(paymentRepository).should().detachUser(USER_ID);
			then(orderRepository).should().detachUser(USER_ID);
		}

		@Test
		@DisplayName("탈퇴 알림 대신 이름과 이메일을 담은 탈퇴 이벤트를 한 번 발행한다")
		void publishesWithdrawnEventOnce() {
			// when
			userService.deleteUser(USERNAME);

			// then
			then(eventPublisher).should()
				.publishEvent(new UserWithdrawnEvent(USER_ID, "탈퇴회원", USERNAME));
		}
	}

	@Nested
	@DisplayName("프로필을 수정하면")
	class WhenUpdatingProfile {

		@Test
		@DisplayName("요청에 담긴 모든 항목을 로그인한 회원에게 반영해 그 회원을 돌려준다")
		void appliesEveryRequestFieldToMember() {
			// given
			User member = User.createByOauth("kakao-1", null, null, "kakao-1", SocialType.KAKAO);
			given(userRepository.findByUsername("kakao-1")).willReturn(Optional.of(member));
			ProfileUpdateRequestDto request = new ProfileUpdateRequestDto();
			request.setName("새이름");
			request.setBirthDate(LocalDate.of(1990, 1, 1));
			request.setBirthTime(LocalTime.of(10, 30));
			request.setBirthPlace(" 부산 ");
			request.setGender(Gender.FEMALE);
			request.setMarketingAgreed(true);

			// when
			User updated = userService.updateUserProfile("kakao-1", request);

			// then
			assertThat(updated).isSameAs(member);
			assertThat(updated.getName()).isEqualTo("새이름");
			assertThat(updated.getBirthDate()).isEqualTo(LocalDate.of(1990, 1, 1));
			assertThat(updated.getBirthTime()).isEqualTo(LocalTime.of(10, 30));
			assertThat(updated.getBirthPlace()).isEqualTo("부산");
			assertThat(updated.getGender()).isEqualTo(Gender.FEMALE);
			assertThat(updated.isMarketingAgreed()).isTrue();
		}

		@Test
		@DisplayName("필수값이 비는 요청이면 400 이 되는 예외를 던진다")
		void rejectsRequestLeavingRequiredFieldEmpty() {
			// given
			User member = User.createByOauth("kakao-1", "소셜회원", null, "kakao-1", SocialType.KAKAO);
			given(userRepository.findByUsername("kakao-1")).willReturn(Optional.of(member));
			ProfileUpdateRequestDto request = new ProfileUpdateRequestDto();
			request.setGender(Gender.MALE);

			// when & then
			assertThatThrownBy(() -> userService.updateUserProfile("kakao-1", request))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("생년월일을 입력해주세요.");
		}
	}

	@Nested
	@DisplayName("사주·궁합 결과 하나를 조회하면")
	class WhenReadingOneResult {

		private static final Long MEMBER_ID = 1L;
		private static final Long RESULT_ID = 100L;

		private User member;

		@BeforeEach
		void loggedIn() {
			member = UserFixture.withId(User.create("member@example.com", "회원", "encoded-password",
				"member@example.com", LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false), MEMBER_ID);
			given(userRepository.findByUsername("member@example.com")).willReturn(Optional.of(member));
		}

		@Test
		@DisplayName("내 사주 결과이면 돌려준다")
		void returnsOwnSajuResult() {
			// given
			Result result = Result.createInitial(MEMBER_ID, 10L, "인생 총운");
			ReflectionTestUtils.setField(result, "id", RESULT_ID);
			given(resultRepository.findById(RESULT_ID)).willReturn(Optional.of(result));

			// when
			InterpretationResultResponse response = userService.getInterpretationResult(RESULT_ID,
				"member@example.com");

			// then
			assertThat(response.getId()).isEqualTo(RESULT_ID);
		}

		@Test
		@DisplayName("내 궁합 결과이면 돌려준다")
		void returnsOwnCompatibilityResult() {
			// given
			CompatibilityResult result = CompatibilityResult.createInitial(MEMBER_ID, 10L, "궁합");
			ReflectionTestUtils.setField(result, "id", RESULT_ID);
			given(compatibilityResultRepository.findById(RESULT_ID)).willReturn(Optional.of(result));

			// when
			ManseCompatibilityAnalysisResponse response = userService.getCompatibilityResultDetail(RESULT_ID,
				"member@example.com");

			// then
			assertThat(response.getResultId()).isEqualTo(RESULT_ID);
		}

		@ParameterizedTest(name = "[{index}] 결과 주인 {0} → 403")
		@NullSource
		@ValueSource(longs = 2L)
		@DisplayName("다른 사람의 사주 결과이거나 주인이 없는(user_id NULL) 결과이면 403 이 되는 예외를 던진다")
		void rejectsSajuResultOfSomeoneElse(Long ownerId) {
			// given
			given(resultRepository.findById(RESULT_ID)).willReturn(
				Optional.of(Result.createInitial(ownerId, 10L, "인생 총운")));

			// when & then
			assertThatThrownBy(() -> userService.getInterpretationResult(RESULT_ID, "member@example.com"))
				.isInstanceOf(AccessDeniedException.class)
				.hasMessage("다른 사람의 리소스에 접근할 수 없습니다.");
		}

		@ParameterizedTest(name = "[{index}] 결과 주인 {0} → 403")
		@NullSource
		@ValueSource(longs = 2L)
		@DisplayName("다른 사람의 궁합 결과이거나 주인이 없는(user_id NULL) 결과이면 403 이 되는 예외를 던진다")
		void rejectsCompatibilityResultOfSomeoneElse(Long ownerId) {
			// given
			given(compatibilityResultRepository.findById(RESULT_ID)).willReturn(
				Optional.of(CompatibilityResult.createInitial(ownerId, 10L, "궁합")));

			// when & then
			assertThatThrownBy(() -> userService.getCompatibilityResultDetail(RESULT_ID, "member@example.com"))
				.isInstanceOf(AccessDeniedException.class)
				.hasMessage("다른 사람의 리소스에 접근할 수 없습니다.");
		}

		@Test
		@DisplayName("없는 사주 결과 id 이면 404 가 되는 예외를 던진다")
		void rejectsUnknownSajuResult() {
			// given
			given(resultRepository.findById(RESULT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> userService.getInterpretationResult(RESULT_ID, "member@example.com"))
				.isInstanceOf(EntityNotFoundException.class)
				.hasMessage("사주 결과를 찾을 수 없습니다. resultId: 100");
		}

		@Test
		@DisplayName("없는 궁합 결과 id 이면 404 가 되는 예외를 던진다")
		void rejectsUnknownCompatibilityResult() {
			// given
			given(compatibilityResultRepository.findById(RESULT_ID)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> userService.getCompatibilityResultDetail(RESULT_ID, "member@example.com"))
				.isInstanceOf(EntityNotFoundException.class)
				.hasMessage("궁합 결과를 찾을 수 없습니다. resultId: 100");
		}
	}

	@Test
	@DisplayName("해석 이력은 사주 결과와 궁합 결과를 한 목록에 섞어 만든 시각이 늦은 것부터 돌려준다")
	void combinedHistoryIsNewestFirstAcrossBothKinds() {
		// given: 저장소는 종류별로 각각 최신순으로 돌려준다
		User member = UserFixture.withId(User.create("member@example.com", "회원", "encoded-password",
			"member@example.com", LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false), 1L);
		given(userRepository.findByUsername("member@example.com")).willReturn(Optional.of(member));
		given(resultRepository.findAllByUserIdOrderByCreatedAtDesc(1L)).willReturn(List.of(
			sajuResult(11L, LocalDateTime.of(2026, 9, 3, 9, 0)),
			sajuResult(12L, LocalDateTime.of(2026, 9, 1, 9, 0))));
		given(compatibilityResultRepository.findByUserIdOrderByCreatedAtDesc(1L)).willReturn(List.of(
			compatibilityResult(21L, LocalDateTime.of(2026, 9, 4, 9, 0)),
			compatibilityResult(22L, LocalDateTime.of(2026, 9, 2, 9, 0))));

		// when
		List<SajuHistoryResponseDto> history = userService.getCombinedSajuHistory("member@example.com");

		// then
		assertThat(history)
			.extracting(SajuHistoryResponseDto::getResultId, SajuHistoryResponseDto::getResultType)
			.containsExactly(
				tuple(21L, ResultType.COMPATIBILITY),
				tuple(11L, ResultType.SAJU),
				tuple(22L, ResultType.COMPATIBILITY),
				tuple(12L, ResultType.SAJU));
	}

	private static Result sajuResult(Long id, LocalDateTime createdAt) {
		Result result = Result.createInitial(1L, id, "인생 총운");
		ReflectionTestUtils.setField(result, "id", id);
		ReflectionTestUtils.setField(result, "createdAt", createdAt);
		return result;
	}

	private static CompatibilityResult compatibilityResult(Long id, LocalDateTime createdAt) {
		CompatibilityResult result = CompatibilityResult.createInitial(1L, id, "궁합");
		ReflectionTestUtils.setField(result, "id", id);
		ReflectionTestUtils.setField(result, "createdAt", createdAt);
		return result;
	}

	@ParameterizedTest(name = "[{index}] {0} 가입 → 가입 경로 [{1}]")
	@CsvSource(textBlock = """
		# 제공자, 가입 이벤트에 담을 가입 경로
		GOOGLE,   GOOGLE OAuth
		KAKAO,    KAKAO OAuth
		NAVER,    NAVER OAuth
		X,        X OAuth
		""")
	@DisplayName("소셜 가입은 가입 알림 대신 제공자 이름 뒤에 OAuth 를 붙인 가입 경로로 가입 이벤트를 한 번 발행한다")
	void registerWithOauth_PublishesRegisteredEventWithProviderSignupPath(SocialType socialType,
		String expectedSignupPath) {
		// given
		givenSaveAssignsId(1L);

		// when
		User saved = userService.registerWithOauth(
			new OauthProfile(socialType, "social-1", null, "소셜회원", false));

		// then
		then(eventPublisher).should().publishEvent(
			new UserRegisteredEvent(1L, "소셜회원", null, expectedSignupPath, saved.getCreatedAt()));
	}

	@ParameterizedTest(name = "[{index}] {0} 가입")
	@EnumSource(value = SocialType.class, mode = EnumSource.Mode.EXCLUDE, names = "NAVER")
	@DisplayName("네이버가 아닌 소셜 가입은 제공자의 사용자 번호를 username 으로 저장한다")
	void registerWithOauth_UsesSocialIdAsUsername(SocialType socialType) {
		// given
		givenSaveAssignsId(1L);

		// when
		User saved = userService.registerWithOauth(
			new OauthProfile(socialType, "social-1", null, "소셜회원", false));

		// then
		assertThat(saved.getUsername()).isEqualTo("social-1");
	}

	@Test
	@DisplayName("네이버 가입은 사용자 번호가 아닌 무작위 10자리 대문자를 username 으로 저장한다")
	void registerWithOauth_UsesRandomUsernameForNaver() {
		// given
		givenSaveAssignsId(1L);

		// when
		User saved = userService.registerWithOauth(
			new OauthProfile(SocialType.NAVER, "naver-id-1", null, "네이버회원", false));

		// then
		assertThat(saved.getUsername())
			.isNotEqualTo("naver-id-1")
			.hasSize(10)
			.isUpperCase();
	}

	@Nested
	@DisplayName("이메일로 가입하면")
	class WhenSigningUpWithEmail {

		private static final String EMAIL = "new@example.com";

		@BeforeEach
		void givenEncodedPassword() {
			given(passwordEncoder.encode("password123")).willReturn("encoded-password");
		}

		@Test
		@DisplayName("가입 알림 대신 '일반 회원가입' 경로를 담은 가입 이벤트를 한 번 발행한다")
		void publishesRegisteredEventOnce() {
			// given
			givenSaveAssignsId(2L);

			// when
			User saved = signUp();

			// then
			then(eventPublisher).should().publishEvent(
				new UserRegisteredEvent(2L, "테스트유저", EMAIL, "일반 회원가입", saved.getCreatedAt()));
		}

		@Test
		@DisplayName("비밀번호 암호화(BCrypt)는 트랜잭션을 열기 전에 끝낸다")
		void encodesPasswordBeforeOpeningTransaction() {
			// given: 암호화하는 순간에 트랜잭션 매니저가 그때까지 받은 호출 수를 적어 둔다
			AtomicInteger transactionCallsWhenEncoding = new AtomicInteger(-1);
			given(passwordEncoder.encode("password123")).willAnswer(invocation -> {
				transactionCallsWhenEncoding.set(mockingDetails(transactionManager).getInvocations().size());
				return "encoded-password";
			});
			givenSaveAssignsId(2L);

			// when
			signUp();

			// then: 암호화하는 수십~100ms 동안 트랜잭션도 DB 커넥션도 쥐지 않는다
			assertThat(transactionCallsWhenEncoding)
				.as("암호화할 때까지 트랜잭션 매니저가 받은 호출 수")
				.hasValue(0);
		}

		@Test
		@DisplayName("같은 이메일의 계정이 이미 있으면 저장하지 않고 DuplicateEmailException 을 던진다")
		void rejectsEmailThatAlreadyExists() {
			// given
			given(userRepository.existsByEmail(EMAIL)).willReturn(true);

			// when & then
			assertThatThrownBy(this::signUp)
				.isInstanceOf(DuplicateEmailException.class)
				.hasMessage("이미 존재하는 이메일 입니다: new@example.com");
			then(userRepository).should(never()).saveAndFlush(any());
			verifyNoInteractions(eventPublisher);
		}

		@Test
		@DisplayName("동시에 들어온 같은 가입이 확인을 지나쳐 저장에서 UNIQUE 에 걸리면 DuplicateEmailException(409)으로 바꾸고 원래 예외를 원인으로 담는다")
		void translatesUniqueViolationToDuplicateEmail() {
			// given
			DataIntegrityViolationException uniqueViolation = uniqueViolation();
			given(userRepository.existsByEmail(EMAIL)).willReturn(false);
			given(userRepository.saveAndFlush(any(User.class))).willThrow(uniqueViolation);

			// when & then
			assertThatThrownBy(this::signUp)
				.isInstanceOf(DuplicateEmailException.class)
				.hasMessage("이미 존재하는 이메일 입니다: new@example.com")
				.hasCause(uniqueViolation);
			verifyNoInteractions(eventPublisher);
		}

		@Test
		@DisplayName("저장이 NOT NULL 처럼 UNIQUE 가 아닌 제약에 걸리면 바꾸지 않고 그 예외를 그대로 던진다")
		void rethrowsViolationThatIsNotUnique() {
			// given
			DataIntegrityViolationException notNullViolation = notNullViolation();
			given(userRepository.existsByEmail(EMAIL)).willReturn(false);
			given(userRepository.saveAndFlush(any(User.class))).willThrow(notNullViolation);

			// when & then
			assertThatThrownBy(this::signUp).isSameAs(notNullViolation);
			verifyNoInteractions(eventPublisher);
		}

		private User signUp() {
			return userService.createUser("테스트유저", EMAIL, "password123", LocalDate.of(1990, 1, 1),
				Gender.MALE, true, false);
		}
	}

	@Nested
	@DisplayName("이메일 가입자의 username 은 이메일이라")
	class UsernameIsEmail {

		private static final String EMAIL = "member@example.com";

		@Test
		@DisplayName("계정을 찾을 때 username 을 로그에 남기지 않는다")
		void findByUsernameDoesNotLogUsername(CapturedOutput output) {
			// given
			given(userRepository.findByUsername(EMAIL)).willReturn(Optional.of(emailSignupMember()));

			// when
			userService.findByUsername(EMAIL);

			// then
			assertThat(output.getAll()).doesNotContain(EMAIL);
		}

		@Test
		@DisplayName("계정이 없을 때 던지는 예외 메시지에 username 을 넣지 않는다(GlobalExceptionHandler 가 WARN 로그로 남긴다)")
		void notFoundMessageHasNoUsername() {
			// given
			given(userRepository.findByUsername(EMAIL)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> userService.findByUsername(EMAIL))
				.isInstanceOf(EntityNotFoundException.class)
				.hasMessage("사용자를 찾을 수 없습니다.");
		}

		@Test
		@DisplayName("탈퇴 완료 로그에는 userId 만 남기고 이메일은 남기지 않는다")
		void deleteUserLogsOnlyUserId(CapturedOutput output) {
			// given
			User member = emailSignupMember();
			UserFixture.withId(member, 7L);
			given(userRepository.findByUsername(EMAIL)).willReturn(Optional.of(member));
			given(userRepository.findByIdForUpdate(7L)).willReturn(Optional.of(member));

			// when
			userService.deleteUser(EMAIL);

			// then
			assertThat(output.getAll())
				.contains("회원 탈퇴 처리 완료: userId=7")
				.doesNotContain(EMAIL);
		}

		private User emailSignupMember() {
			return User.create(EMAIL, "이메일회원", "encoded-password", EMAIL, LocalDate.of(1990, 1, 1),
				Gender.FEMALE, true, true, false);
		}
	}

	@Test
	@DisplayName("가입하지 않은 이메일로 재설정 메일을 요청하면 토큰도 이벤트도 메일도 만들지 않는다")
	void requestPasswordReset_DoesNothingForUnknownEmail() {
		// given
		given(userRepository.findByEmail("nobody@example.com")).willReturn(Optional.empty());

		// when
		userService.requestPasswordReset("nobody@example.com");

		// then: 계정이 있는지 알려 주지 않도록 아무 일도 일어나지 않는다
		verifyNoInteractions(passwordResetTokenRepository, eventPublisher, emailService);
	}

	@Test
	@DisplayName("소셜 가입자가 재설정 메일을 요청하면 비밀번호가 없다는 안내 메일을 보내고 재설정 토큰은 만들지 않는다")
	void requestPasswordReset_SendsSocialLoginGuideToSocialMember() {
		// given
		User socialMember = User.createByOauth("kakao-1", "소셜회원", "social@example.com", "kakao-1",
			SocialType.KAKAO);
		given(userRepository.findByEmail("social@example.com")).willReturn(Optional.of(socialMember));

		// when
		userService.requestPasswordReset("social@example.com");

		// then
		then(emailService).should().sendSocialLoginGuideEmail("social@example.com", "소셜회원");
		verifyNoInteractions(passwordResetTokenRepository, eventPublisher);
	}

	@Nested
	@DisplayName("이메일 가입자가 비밀번호 재설정 메일을 요청하면")
	class WhenEmailMemberRequestsPasswordReset {

		private static final String EMAIL = "reset@example.com";
		private static final Long USER_ID = 11L;

		private User member;

		@BeforeEach
		void givenEmailSignupMember() {
			member = User.create(EMAIL, "재설정회원", "old-hash", EMAIL, LocalDate.of(1990, 1, 1), Gender.FEMALE,
				true, true, false);
			UserFixture.withId(member, USER_ID);
			given(userRepository.findByEmail(EMAIL)).willReturn(Optional.of(member));
			// 사용자 행 잠금. 이 스텁을 쓰지 않으면(잠그지 않으면) strict stubs 가 테스트를 실패시킨다.
			given(userRepository.findByIdForUpdate(USER_ID)).willReturn(Optional.of(member));
		}

		@Test
		@DisplayName("토큰 행이 없으면 지금부터 15분 동안 쓸 수 있는 토큰을 새로 넣는다")
		void insertsNewTokenWhenMemberHasNone() {
			// given
			given(passwordResetTokenRepository.findByUserId(USER_ID)).willReturn(Optional.empty());
			given(passwordResetTokenRepository.save(any(PasswordResetToken.class)))
				.willAnswer(invocation -> invocation.getArgument(0));

			// when
			userService.requestPasswordReset(EMAIL);

			// then
			ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
			then(passwordResetTokenRepository).should().save(saved.capture());
			assertThat(saved.getValue().getUser()).isSameAs(member);
			assertThat(saved.getValue().getExpiryDate()).isEqualTo(LocalDateTime.of(2026, 9, 26, 15, 0));
		}

		@ParameterizedTest(name = "[{index}] {0} 에 발급한 토큰 → 만료 시각 {1}, 값이 그대로 {2}")
		@CsvSource(textBlock = """
			# 발급 시각,       요청 뒤 만료 시각,   토큰 값이 그대로인지
			# 만료 14:30. 이미 만료돼 값을 바꾸고 지금(14:45)부터 15분 뒤로 옮긴다.
			2026-09-26T14:15, 2026-09-26T15:00, false
			# 만료 14:45. 만료 시각이 지금과 같으면 만료로 본다.
			2026-09-26T14:30, 2026-09-26T15:00, false
			# 만료 14:46. 아직 쓸 수 있어 값도 만료 시각도 그대로 둔다.
			2026-09-26T14:31, 2026-09-26T14:46, true
			""")
		@DisplayName("토큰 행이 있으면 새로 넣지 않고, 만료됐을 때만 그 행의 토큰 값을 바꾸고 만료 시각을 지금부터 15분 뒤로 옮긴다")
		void reissuesExistingTokenOnlyWhenExpired(LocalDateTime issuedAt, LocalDateTime expectedExpiry,
			boolean expectedSameValue) {
			// given
			PasswordResetToken existing = new PasswordResetToken(member, issuedAt);
			String oldValue = existing.getToken();
			given(passwordResetTokenRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));

			// when
			userService.requestPasswordReset(EMAIL);

			// then
			assertThat(existing.getExpiryDate()).isEqualTo(expectedExpiry);
			assertThat(existing.getToken().equals(oldValue)).as("앞서 보낸 링크의 토큰 값이 그대로인지")
				.isEqualTo(expectedSameValue);
			then(passwordResetTokenRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("아직 만료 전인 토큰 행이 있으면 앞서 보낸 링크와 같은 토큰을 담은 이벤트를 발행해 같은 링크를 다시 보낸다")
		void publishesSameTokenWhenNotExpired() {
			// given: 10분 전에 요청해 5분 더 쓸 수 있는 토큰
			PasswordResetToken existing = new PasswordResetToken(member, LocalDateTime.of(2026, 9, 26, 14, 35));
			String mailedBefore = existing.getToken();
			given(passwordResetTokenRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));

			// when
			userService.requestPasswordReset(EMAIL);

			// then
			then(eventPublisher).should().publishEvent(new PasswordResetRequestedEvent(USER_ID, EMAIL, mailedBefore));
		}

		@Test
		@DisplayName("요청 트랜잭션 안에서는 메일을 보내지 않고, 커밋 뒤 메일에 쓸 새 토큰 값을 담은 이벤트를 한 번 발행한다")
		void publishesEventInsteadOfSendingMailInsideTransaction() {
			// given
			PasswordResetToken existing = new PasswordResetToken(member, LocalDateTime.of(2026, 9, 26, 14, 15));
			given(passwordResetTokenRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));

			// when
			userService.requestPasswordReset(EMAIL);

			// then
			then(emailService).should(never()).sendPasswordResetEmail(anyString(), anyString());
			then(eventPublisher).should()
				.publishEvent(new PasswordResetRequestedEvent(USER_ID, EMAIL, existing.getToken()));
		}
	}

	@Nested
	@DisplayName("메일로 받은 토큰으로 비밀번호를 재설정하면")
	class WhenResettingPassword {

		// FIXED_CLOCK(14:45) 기준으로 아직 쓸 수 있는 토큰의 발급 시각(만료 14:50)과 이미 만료된 토큰의 발급 시각(만료 14:40)
		private static final LocalDateTime ISSUED_TEN_MINUTES_AGO = LocalDateTime.of(2026, 9, 26, 14, 35);
		private static final LocalDateTime ISSUED_TWENTY_MINUTES_AGO = LocalDateTime.of(2026, 9, 26, 14, 25);
		private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 26, 14, 45);

		private User member;

		@BeforeEach
		void givenMember() {
			member = User.create("member@example.com", "재설정회원", "old-hash", "member@example.com",
				LocalDate.of(1990, 1, 1), Gender.FEMALE, true, true, false);
			UserFixture.withId(member, 12L);
		}

		@Test
		@DisplayName("토큰을 쓰는 조건부 DELETE 가 1 이면 비밀번호 해시를 새 값으로 바꾼다")
		void changesPasswordWhenTokenIsConsumed() {
			// given
			String token = givenStoredToken(ISSUED_TEN_MINUTES_AGO);
			given(passwordEncoder.encode("new-password")).willReturn("new-hash");
			givenMemberRowLocked();
			given(passwordResetTokenRepository.consume(token, NOW)).willReturn(1);

			// when
			userService.resetPassword(token, "new-password");

			// then
			assertThat(member.getPassword()).isEqualTo("new-hash");
		}

		@Test
		@DisplayName("비밀번호를 바꾸면 같은 트랜잭션에서 그 사용자의 리프레시 토큰을 한 번 모두 폐기한다")
		void revokesAllRefreshTokensOnce() {
			// given
			String token = givenStoredToken(ISSUED_TEN_MINUTES_AGO);
			given(passwordEncoder.encode("new-password")).willReturn("new-hash");
			givenMemberRowLocked();
			given(passwordResetTokenRepository.consume(token, NOW)).willReturn(1);

			// when
			userService.resetPassword(token, "new-password");

			// then
			then(refreshTokenRepository).should(times(1)).revokeAllUserTokens(member);
		}

		@Test
		@DisplayName("그사이 다른 요청이 토큰을 먼저 써서 조건부 DELETE 가 0 이면 400 이고 비밀번호는 그대로다")
		void rejectsTokenAlreadyConsumed() {
			// given
			String token = givenStoredToken(ISSUED_TEN_MINUTES_AGO);
			given(passwordEncoder.encode("new-password")).willReturn("new-hash");
			givenMemberRowLocked();
			given(passwordResetTokenRepository.consume(token, NOW)).willReturn(0);

			// when & then: 토큰을 쓰기 전에 한 리프레시 토큰 폐기는 예외로 롤백된다(PasswordResetMySqlTest 가 본다)
			assertThatThrownBy(() -> userService.resetPassword(token, "new-password"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("이미 사용되었거나 만료된 토큰입니다.");
			assertThat(member.getPassword()).isEqualTo("old-hash");
		}

		@Test
		@DisplayName("그사이 회원이 탈퇴해 사용자 행이 없으면 400 '유효하지 않은 토큰입니다.' 이고 토큰을 쓰지 않는다")
		void rejectsWhenMemberWithdrewMeanwhile() {
			// given
			String token = givenStoredToken(ISSUED_TEN_MINUTES_AGO);
			given(passwordEncoder.encode("new-password")).willReturn("new-hash");
			given(userRepository.findByIdForUpdate(12L)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> userService.resetPassword(token, "new-password"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("유효하지 않은 토큰입니다.");
			then(passwordResetTokenRepository).should(never()).consume(anyString(), any());
		}

		@Test
		@DisplayName("만료된 토큰이면 400 이고, 토큰을 쓰지도 지우지도 않으며 비밀번호도 그대로다")
		void rejectsExpiredTokenWithoutDeletingIt() {
			// given
			String token = givenStoredToken(ISSUED_TWENTY_MINUTES_AGO);

			// when & then
			assertThatThrownBy(() -> userService.resetPassword(token, "new-password"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("만료된 토큰입니다. 다시 요청해주세요.");
			assertThat(member.getPassword()).isEqualTo("old-hash");
			then(passwordResetTokenRepository).should(never()).consume(anyString(), any());
			then(passwordResetTokenRepository).should(never()).delete(any());
			then(refreshTokenRepository).should(never()).revokeAllUserTokens(any());
		}

		@Test
		@DisplayName("없는 토큰이면 400 '유효하지 않은 토큰입니다.' 이다")
		void rejectsUnknownToken() {
			// given
			given(passwordResetTokenRepository.findByToken("unknown-token")).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> userService.resetPassword("unknown-token", "new-password"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("유효하지 않은 토큰입니다.");
		}

		/**
		 * 재설정 토큰을 쓰기 전에 잠그는 사용자 행이 아직 있게 한다.
		 */
		private void givenMemberRowLocked() {
			given(userRepository.findByIdForUpdate(12L)).willReturn(Optional.of(member));
		}

		/**
		 * issuedAt 에 member 에게 발급한 토큰이 저장돼 있게 하고, 그 토큰 값을 돌려준다.
		 */
		private String givenStoredToken(LocalDateTime issuedAt) {
			PasswordResetToken stored = new PasswordResetToken(member, issuedAt);
			given(passwordResetTokenRepository.findByToken(stored.getToken())).willReturn(Optional.of(stored));
			return stored.getToken();
		}
	}

	/**
	 * 저장하면 IDENTITY 가 id 를 채우는 것처럼, 받은 User 에 id 를 넣어 돌려준다.
	 */
	private void givenSaveAssignsId(Long id) {
		given(userRepository.saveAndFlush(any(User.class))).willAnswer(invocation -> {
			User user = invocation.getArgument(0);
			UserFixture.withId(user, id);
			return user;
		});
	}

	// Hibernate MySQL 방언이 중복 키(1062)에 만드는 모양. 실제로 이 모양이 되는지는 UniqueConstraintViolationsMySqlTest 가 본다.
	private static DataIntegrityViolationException uniqueViolation() {
		return new DataIntegrityViolationException("could not execute statement",
			new ConstraintViolationException("could not execute statement",
				new SQLIntegrityConstraintViolationException(
					"Duplicate entry 'new@example.com' for key 'users.uk_users_email'", "23000", 1062),
				"insert into users (email) values (?)", ConstraintKind.UNIQUE, "users.uk_users_email"));
	}

	// NOT NULL 위반(1048)은 종류를 따로 적지 않아 ConstraintKind.OTHER 가 된다.
	private static DataIntegrityViolationException notNullViolation() {
		return new DataIntegrityViolationException("could not execute statement",
			new ConstraintViolationException("could not execute statement",
				new SQLIntegrityConstraintViolationException("Column 'username' cannot be null", "23000", 1048),
				"insert into users (username) values (?)"));
	}
}
