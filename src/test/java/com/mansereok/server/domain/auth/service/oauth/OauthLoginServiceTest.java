package com.mansereok.server.domain.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;

import com.mansereok.server.domain.auth.dto.response.oauth.XProfileDto;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.DuplicateEmailException;
import com.mansereok.server.support.fixture.UserFixture;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.util.Optional;
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
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 소셜 로그인의 "계정 찾기, 없으면 가입" 규칙을 확인한다.
 *
 * <p>가입은 진짜 {@link UserService} 가 한다. 목으로 두는 것은 저장소(돌려줄 값만 정한다), 가입 이벤트 발행기, 트랜잭션
 * 매니저뿐이다. 저장소 스텁에는 정확한 인자를 넣어, 다른 제공자나 다른 번호로 조회하면 strict stubs 가 테스트를 실패시킨다.
 */
@ExtendWith(MockitoExtension.class)
class OauthLoginServiceTest {

	private static final long NEW_USER_ID = 500L;

	@Mock
	private UserRepository userRepository;
	@Mock
	private ApplicationEventPublisher eventPublisher;
	// UserService 가 이 목으로 진짜 TransactionTemplate 을 만든다. 커밋·롤백은 아무것도 하지 않는다.
	@Mock
	private PlatformTransactionManager transactionManager;

	// 생성자 주입. 이 테스트가 목으로 두지 않은 UserService 의 협력 객체는 null 로 들어가며, 가입 흐름에서는 쓰이지 않는다.
	@InjectMocks
	private UserService userService;

	private OauthLoginService oauthLoginService;

	@BeforeEach
	void setUp() {
		oauthLoginService = new OauthLoginService(userRepository, userService);
	}

	@Nested
	@DisplayName("같은 제공자의 같은 사용자 번호로 가입한 계정이 있으면")
	class WhenSameSocialAccountExists {

		@Test
		@DisplayName("그 계정으로 로그인하고 새로 가입하지 않는다")
		void logsIntoThatAccount() {
			// given
			User kakaoUser = socialSignupUser(10L, SocialType.KAKAO, "k-100");
			given(userRepository.findBySocialTypeAndSocialId(SocialType.KAKAO, "k-100"))
				.willReturn(Optional.of(kakaoUser));

			// when
			OauthLoginResult result = oauthLoginService.loginOrRegister(
				new OauthProfile(SocialType.KAKAO, "k-100", "k@example.com", "카카오", true));

			// then
			assertThat(result.user()).isSameAs(kakaoUser);
			assertThat(result.newlyRegistered()).isFalse();
			then(userRepository).should(never()).saveAndFlush(any());
		}
	}

	@Nested
	@DisplayName("제공자가 이메일을 주지 않으면")
	class WhenProviderGivesNoEmail {

		@ParameterizedTest(name = "[{index}] {0} 로그인, 이메일 [{1}]")
		@CsvSource(nullValues = "NULL", textBlock = """
			# 제공자, 제공자가 준 이메일
			KAKAO,    NULL
			KAKAO,    '   '
			NAVER,    NULL
			NAVER,    ''
			GOOGLE,   NULL
			GOOGLE,   '  '
			X,        NULL
			""")
		@DisplayName("이메일로 계정을 찾지 않고 이메일 없는 자기 새 계정으로 가입한다")
		void registersNewAccountWithoutLookingUpByEmail(SocialType socialType, String email) {
			// given
			given(userRepository.findBySocialTypeAndSocialId(socialType, "id-1"))
				.willReturn(Optional.empty());
			// 예전 파생 쿼리처럼 이메일 null 조회에 이메일 없는 다른 회원을 돌려주게 해 둔다. 이메일 없이 이메일로 찾으면 그 회원의
			// 계정을 받게 되어 아래 결과 단언이 실패한다. 옳은 코드는 이 조회를 부르지 않으므로 lenient 로 둔다.
			lenient().when(userRepository.findByEmail(null))
				.thenReturn(Optional.of(socialSignupUser(30L, SocialType.KAKAO, "someone-else")));
			givenSaveAssignsNewId();

			// when
			OauthLoginResult result = oauthLoginService.loginOrRegister(
				new OauthProfile(socialType, "id-1", email, "이름", true));

			// then
			assertThat(result.newlyRegistered()).isTrue();
			assertThat(result.user()).extracting(User::getId, User::getEmail, User::getSocialType,
					User::getSocialId)
				.containsExactly(NEW_USER_ID, null, socialType, "id-1");
		}
	}

	@Nested
	@DisplayName("제공자가 주인을 확인하지 않은 이메일이면")
	class WhenEmailIsNotConfirmedByProvider {

		@ParameterizedTest(name = "[{index}] {0} 로그인")
		@EnumSource(value = SocialType.class, names = {"GOOGLE", "KAKAO"})
		@DisplayName("같은 이메일의 기존 계정에 붙지 않고, 그 이메일을 저장하지 않은 새 계정으로 가입한다")
		void registersNewAccountWithoutEmail(SocialType socialType) {
			// given
			given(userRepository.findBySocialTypeAndSocialId(socialType, "id-1"))
				.willReturn(Optional.empty());
			// 그 이메일로 가입한 기존 회원이 있다. 확인되지 않은 이메일로 연동하면 이 계정을 받게 되어 아래 결과 단언이 실패한다.
			// 옳은 코드는 이 조회를 부르지 않으므로 lenient 로 둔다.
			lenient().when(userRepository.findByEmail("victim@example.com"))
				.thenReturn(Optional.of(emailSignupUser(30L, "victim@example.com")));
			givenSaveAssignsNewId();

			// when
			OauthLoginResult result = oauthLoginService.loginOrRegister(
				new OauthProfile(socialType, "id-1", "victim@example.com", "이름", false));

			// then
			assertThat(result.newlyRegistered()).isTrue();
			assertThat(result.user()).extracting(User::getId, User::getEmail)
				.containsExactly(NEW_USER_ID, null);
		}
	}

	@Nested
	@DisplayName("제공자가 주인을 확인한 이메일이면")
	class WhenEmailIsConfirmedByProvider {

		@Test
		@DisplayName("같은 이메일로 가입한 기존 계정으로 로그인하고 새로 가입하지 않는다")
		void logsIntoAccountWithSameEmail() {
			// given
			User emailUser = emailSignupUser(20L, "a@example.com");
			given(userRepository.findBySocialTypeAndSocialId(SocialType.GOOGLE, "g-1"))
				.willReturn(Optional.empty());
			given(userRepository.findByEmail("a@example.com")).willReturn(Optional.of(emailUser));

			// when
			OauthLoginResult result = oauthLoginService.loginOrRegister(
				new OauthProfile(SocialType.GOOGLE, "g-1", "a@example.com", "구글", true));

			// then
			assertThat(result.user()).isSameAs(emailUser);
			assertThat(result.newlyRegistered()).isFalse();
			then(userRepository).should(never()).saveAndFlush(any());
		}

		@Test
		@DisplayName("같은 이메일의 계정이 없으면 그 이메일로 새 계정을 만든다")
		void registersWithConfirmedEmail() {
			// given
			given(userRepository.findBySocialTypeAndSocialId(SocialType.GOOGLE, "g-1"))
				.willReturn(Optional.empty());
			given(userRepository.findByEmail("a@example.com")).willReturn(Optional.empty());
			givenSaveAssignsNewId();

			// when
			OauthLoginResult result = oauthLoginService.loginOrRegister(
				new OauthProfile(SocialType.GOOGLE, "g-1", "a@example.com", "구글", true));

			// then
			assertThat(result.newlyRegistered()).isTrue();
			assertThat(result.user().getEmail()).isEqualTo("a@example.com");
		}
	}

	@Nested
	@DisplayName("X 로 처음 로그인하면")
	class WhenFirstXLogin {

		@Test
		@DisplayName("확인된 이메일은 email 에, 표시 이름은 name 에 저장한다")
		void savesEmailAndDisplayNameInTheirOwnColumns() {
			// given
			given(userRepository.findBySocialTypeAndSocialId(SocialType.X, "x1"))
				.willReturn(Optional.empty());
			given(userRepository.findByEmail("minji@x.com")).willReturn(Optional.empty());
			givenSaveAssignsNewId();

			// when
			oauthLoginService.loginOrRegister(xProfile("x1", "민지", "minji@x.com").toOauthProfile());

			// then
			ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
			then(userRepository).should().saveAndFlush(savedUser.capture());
			assertThat(savedUser.getValue()).extracting(User::getEmail, User::getName)
				.containsExactly("minji@x.com", "민지");
		}

		@Test
		@DisplayName("확인된 이메일이 없으면 email 을 빈 문자열이 아닌 null 로 저장한다")
		void savesNullEmailWhenNoConfirmedEmail() {
			// given
			given(userRepository.findBySocialTypeAndSocialId(SocialType.X, "x1"))
				.willReturn(Optional.empty());
			givenSaveAssignsNewId();

			// when
			oauthLoginService.loginOrRegister(xProfile("x1", "민지", null).toOauthProfile());

			// then
			ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
			then(userRepository).should().saveAndFlush(savedUser.capture());
			assertThat(savedUser.getValue()).extracting(User::getEmail, User::getName)
				.containsExactly(null, "민지");
		}
	}

	@Nested
	@DisplayName("찾을 때 없던 계정이 가입하는 사이에 먼저 저장되면")
	class WhenAccountIsRegisteredMeanwhile {

		@Test
		@DisplayName("같은 소셜 계정의 저장이 UNIQUE 에 걸리면 같은 소셜 계정을 다시 찾아 먼저 가입한 계정으로 로그인한다")
		void logsIntoAccountOfSameSocialIdRegisteredMeanwhile() {
			// given: 첫 조회에는 없고, 다른 요청이 먼저 저장·커밋한 뒤의 다시 찾기에는 있다
			User registeredMeanwhile = socialSignupUser(40L, SocialType.KAKAO, "k-1");
			given(userRepository.findBySocialTypeAndSocialId(SocialType.KAKAO, "k-1"))
				.willReturn(Optional.empty())
				.willReturn(Optional.of(registeredMeanwhile));
			given(userRepository.saveAndFlush(any(User.class))).willThrow(uniqueViolation());

			// when
			OauthLoginResult result = oauthLoginService.loginOrRegister(
				new OauthProfile(SocialType.KAKAO, "k-1", null, "카카오", false));

			// then
			assertThat(result.user()).isSameAs(registeredMeanwhile);
			assertThat(result.newlyRegistered()).isFalse();
		}

		@Test
		@DisplayName("같은 확인된 이메일의 계정이 먼저 생겨 저장이 UNIQUE 에 걸리면 처음과 같은 규칙대로 그 이메일 계정으로 로그인한다")
		void logsIntoAccountOfSameTrustedEmailRegisteredMeanwhile() {
			// given: 같은 이메일의 이메일 가입이 동시에 먼저 저장·커밋됐다
			User emailUser = emailSignupUser(41L, "a@example.com");
			given(userRepository.findBySocialTypeAndSocialId(SocialType.GOOGLE, "g-1"))
				.willReturn(Optional.empty());
			given(userRepository.findByEmail("a@example.com"))
				.willReturn(Optional.empty())
				.willReturn(Optional.of(emailUser));
			given(userRepository.existsByEmail("a@example.com")).willReturn(false);
			given(userRepository.saveAndFlush(any(User.class))).willThrow(uniqueViolation());

			// when
			OauthLoginResult result = oauthLoginService.loginOrRegister(
				new OauthProfile(SocialType.GOOGLE, "g-1", "a@example.com", "구글", true));

			// then
			assertThat(result.user()).isSameAs(emailUser);
			assertThat(result.newlyRegistered()).isFalse();
		}

		@Test
		@DisplayName("다시 찾아도 계정이 없으면(다른 제공자 가입자와 username 이 겹친 경우 등) DuplicateEmailException 을 그대로 던진다")
		void rethrowsWhenNoAccountFoundAgain() {
			// given
			given(userRepository.findBySocialTypeAndSocialId(SocialType.GOOGLE, "1234"))
				.willReturn(Optional.empty());
			given(userRepository.saveAndFlush(any(User.class))).willThrow(uniqueViolation());

			// when & then
			assertThatThrownBy(() -> oauthLoginService.loginOrRegister(
				new OauthProfile(SocialType.GOOGLE, "1234", null, "구글", false)))
				.isInstanceOf(DuplicateEmailException.class)
				.hasMessage("이미 가입된 계정과 겹쳐 가입할 수 없습니다.");
		}
	}

	/**
	 * 저장하면 IDENTITY 가 id 를 채우는 것처럼, 받은 User 에 새 id 를 넣어 돌려준다.
	 */
	private void givenSaveAssignsNewId() {
		given(userRepository.saveAndFlush(any(User.class))).willAnswer(invocation -> {
			User user = invocation.getArgument(0);
			UserFixture.withId(user, NEW_USER_ID);
			return user;
		});
	}

	private static User socialSignupUser(Long id, SocialType socialType, String socialId) {
		User user = User.createByOauth(socialId, "기존 소셜 회원", null, socialId, socialType);
		UserFixture.withId(user, id);
		return user;
	}

	private static User emailSignupUser(Long id, String email) {
		User user = User.create(email, "기존 이메일 회원", "encoded-password", email,
			LocalDate.of(1990, 1, 1), Gender.FEMALE, true, true, false);
		UserFixture.withId(user, id);
		return user;
	}

	// Hibernate MySQL 방언이 중복 키(1062)에 만드는 모양. 실제로 이 모양이 되는지는 UniqueConstraintViolationsMySqlTest 가 본다.
	private static DataIntegrityViolationException uniqueViolation() {
		return new DataIntegrityViolationException("could not execute statement",
			new ConstraintViolationException("could not execute statement",
				new SQLIntegrityConstraintViolationException(
					"Duplicate entry 'k-1-KAKAO' for key 'users.uk_users_social_id_type'", "23000", 1062),
				"insert into users (social_id, social_type) values (?, ?)", ConstraintKind.UNIQUE,
				"users.uk_users_social_id_type"));
	}

	private static XProfileDto xProfile(String id, String displayName, String confirmedEmail) {
		XProfileDto xProfileDto = new XProfileDto();
		xProfileDto.setId(id);
		xProfileDto.setName(displayName);
		xProfileDto.setEmail(confirmedEmail);
		return xProfileDto;
	}
}
