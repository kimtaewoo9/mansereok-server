package com.mansereok.server.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.auth.PasswordResetTokenRepository;
import com.mansereok.server.domain.auth.service.oauth.OauthProfile;
import com.mansereok.server.domain.user.dto.request.ProfileUpdateRequestDto;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.event.UserRegisteredEvent;
import com.mansereok.server.domain.user.event.UserWithdrawnEvent;
import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.DuplicateEmailException;
import jakarta.persistence.EntityNotFoundException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.time.LocalTime;
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
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * UserService 의 가입·탈퇴·프로필 규칙을 확인한다.
 *
 * <p>UserService 는 Discord·Slack 알림 서비스를 갖지 않는다. 가입·탈퇴 알림은 발행한 이벤트를 UserNotificationListener 가 커밋
 * 뒤에 받아 보내므로, 여기서는 이벤트가 한 번, 알맞은 값으로 발행되는지만 본다. 알림 내용은 UserNotificationListenerTest 가,
 * 커밋 뒤에만 나가는지는 WithdrawalMySqlTest 가 본다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
public class UserServiceTest {

	@InjectMocks
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
			member.setId(USER_ID);
			given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(member));
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

	@Test
	@DisplayName("프로필 수정: marketingAgreed=true여도 birthTime 없이 저장된다")
	void updateUserProfile_ShouldAllowNullBirthTime_WhenMarketingAgreedTrue() {
		String username = "tester";
		User user = User.create(
			username,
			"테스터",
			"pw",
			"t@test.com",
			LocalDate.of(1998, 9, 2),
			Gender.MALE,
			true,
			true,
			false
		);
		user.setBirthTime(null);

		ProfileUpdateRequestDto request = new ProfileUpdateRequestDto();
		request.setMarketingAgreed(true);
		request.setBirthTime(null);

		given(userRepository.findByUsername(username)).willReturn(Optional.of(user));
		given(userRepository.save(user)).willReturn(user);

		User saved = userService.updateUserProfile(username, request);

		assertThat(saved.isMarketingAgreed()).isTrue();
		assertThat(saved.getBirthTime()).isNull();
		verify(userRepository, times(1)).save(user);
	}

	@Test
	@DisplayName("프로필 수정: 이름/생년월일/성별은 marketingAgreed와 무관하게 필수다")
	void updateUserProfile_ShouldRequireNameBirthDateGender() {
		String username = "tester";
		User user = User.create(
			username,
			"테스터",
			"pw",
			"t@test.com",
			LocalDate.of(1998, 9, 2),
			Gender.MALE,
			true,
			true,
			false
		);
		user.setName(null);

		ProfileUpdateRequestDto request = new ProfileUpdateRequestDto();
		request.setMarketingAgreed(false);

		given(userRepository.findByUsername(username)).willReturn(Optional.of(user));

		assertThatThrownBy(() -> userService.updateUserProfile(username, request))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("이름을 입력해주세요.");
	}

	@Test
	@DisplayName("프로필 수정: birthPlace는 선택값이지만 공백 문자열은 허용하지 않는다")
	void updateUserProfile_ShouldRejectBlankBirthPlace() {
		String username = "tester";
		User user = User.create(
			username,
			"테스터",
			"pw",
			"t@test.com",
			LocalDate.of(1998, 9, 2),
			Gender.MALE,
			true,
			true,
			false
		);
		user.setBirthTime(LocalTime.of(10, 30));

		ProfileUpdateRequestDto request = new ProfileUpdateRequestDto();
		request.setBirthPlace("   ");

		given(userRepository.findByUsername(username)).willReturn(Optional.of(user));

		assertThatThrownBy(() -> userService.updateUserProfile(username, request))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("태어난 장소는 공백일 수 없습니다.");
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

	@ParameterizedTest(name = "[{index}] 이메일 [{0}]")
	@NullAndEmptySource
	@ValueSource(strings = {"   "})
	@DisplayName("이메일이 null 이거나 공백이면 저장소를 조회하지 않고 빈 값을 돌려준다")
	void findByEmail_ReturnsEmptyWithoutQueryForBlankEmail(String email) {
		// when
		Optional<User> found = userService.findByEmail(email);

		// then
		assertThat(found).isEmpty();
		verifyNoInteractions(userRepository);
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
			member.setId(7L);
			given(userRepository.findByUsername(EMAIL)).willReturn(Optional.of(member));

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

	/**
	 * 저장하면 IDENTITY 가 id 를 채우는 것처럼, 받은 User 에 id 를 넣어 돌려준다.
	 */
	private void givenSaveAssignsId(Long id) {
		given(userRepository.saveAndFlush(any(User.class))).willAnswer(invocation -> {
			User user = invocation.getArgument(0);
			user.setId(id);
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
