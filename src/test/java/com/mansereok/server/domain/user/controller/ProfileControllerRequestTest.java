package com.mansereok.server.domain.user.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mansereok.server.domain.auth.PasswordResetTokenRepository;
import com.mansereok.server.domain.auth.util.RefreshTokenCookies;
import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.EmailService;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.GlobalExceptionHandler;
import com.mansereok.server.global.exception.RequestErrorExceptionHandler;
import com.mansereok.server.support.fixture.UserFixture;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * 프로필 수정과 결과 조회 API 가 요청에 돌려주는 상태 코드와 본문을 확인한다.
 *
 * <p>운영과 같게 두 예외 처리기를 등록한 standalone MockMvc 로 부른다. 프로필 규칙은 User 가 지키므로 UserService 는 진짜를 쓰고,
 * 회원·결과 조회만 저장소 스텁으로 정한다. 트랜잭션 프록시가 없으므로 변경 감지로 저장되는지는 보지 않는다
 * (ProfileUpdateMySqlTest 가 본다).
 */
class ProfileControllerRequestTest {

	private static final String USERNAME = "member@example.com";
	private static final String GOOGLE_USERNAME = "google-sub-2";
	private static final String PROFILE_URL = "/api/v1/users/me/profiles";

	private final UserRepository userRepository = mock(UserRepository.class);
	private final ResultRepository resultRepository = mock(ResultRepository.class);
	private final CompatibilityResultRepository compatibilityResultRepository =
		mock(CompatibilityResultRepository.class);

	private User member;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		UserService userService = new UserService(userRepository, resultRepository, compatibilityResultRepository,
			mock(RefreshTokenRepository.class), mock(OrderRepository.class), mock(PaymentRepository.class),
			mock(PasswordResetTokenRepository.class), mock(PasswordEncoder.class), mock(EmailService.class),
			mock(ReviewRepository.class), mock(ApplicationEventPublisher.class),
			mock(PlatformTransactionManager.class), Clock.systemUTC());
		mockMvc = MockMvcBuilders.standaloneSetup(
				new ProfileController(userService, mock(RefreshTokenCookies.class)))
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.setControllerAdvice(new GlobalExceptionHandler(), new RequestErrorExceptionHandler())
			.build();

		// JWT 필터가 하는 것처럼 회원 아이디를 principal 로 넣는다
		member = UserFixture.withId(User.create(USERNAME, "기존이름", "encoded-password", USERNAME,
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false), 1L);
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(member));
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
			USERNAME, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Nested
	@DisplayName("프로필을 수정할 때")
	class WhenUpdatingProfile {

		@Test
		@DisplayName("gender 가 열거 상수 이름이 아닌 male 이면 400 INVALID_REQUEST_BODY 로 보낸 값을 알려 주고 프로필을 바꾸지 않는다")
		void rejectsLowercaseGender() throws Exception {
			updateProfile("{\"name\": \"새이름\", \"gender\": \"male\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST_BODY"))
				.andExpect(jsonPath("$.message").value("요청 본문의 gender 값 'male' 이 올바른 형식이 아닙니다."));
			assertThat(member.getName()).isEqualTo("기존이름");
		}

		@Test
		@DisplayName("태어난 장소가 공백뿐이면 400 INVALID_INPUT 이고 같이 보낸 이름도 바꾸지 않는다")
		void rejectsBlankBirthPlace() throws Exception {
			updateProfile("{\"name\": \"새이름\", \"birthPlace\": \"   \"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("태어난 장소는 공백일 수 없습니다."));
			assertThat(member.getName()).isEqualTo("기존이름");
		}

		@Test
		@DisplayName("이름을 가입과 같은 한도인 20자를 넘는 다른 이름으로 바꾸려 하면 400 INVALID_INPUT 이다")
		void rejectsNewNameLongerThanSignupLimit() throws Exception {
			updateProfile("{\"name\": \"" + "가".repeat(21) + "\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("이름은 20자까지 입력할 수 있습니다."));
			assertThat(member.getName()).isEqualTo("기존이름");
		}

		@Test
		@DisplayName("소셜 가입 때 받은 20자 넘는 이름을 그대로 담아 생년월일·성별과 함께 보내면 200 이고 생년월일·성별을 저장한다")
		void acceptsCurrentProviderNameLongerThanSignupLimit() throws Exception {
			// given: 구글이 준 27자 이름으로 가입하고 생년월일·성별은 아직 없는 회원
			User googleMember = logInAsGoogleMemberNamed("Christopher Alexander Smith");

			// when & then
			updateProfile("""
				{"name": "Christopher Alexander Smith", "birthDate": "1990-01-01", "gender": "MALE"}
				""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Christopher Alexander Smith"))
				.andExpect(jsonPath("$.newUser").value(false));
			assertThat(googleMember.getBirthDate()).isEqualTo(LocalDate.of(1990, 1, 1));
			assertThat(googleMember.getGender()).isEqualTo(Gender.MALE);
		}

		@Test
		@DisplayName("gender 가 빈 문자열이면 성별을 보내지 않은 것으로 보고 200 이며 같이 보낸 이름은 바꾼다")
		void treatsEmptyGenderAsNotSent() throws Exception {
			updateProfile("{\"name\": \"새이름\", \"gender\": \"\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("새이름"))
				.andExpect(jsonPath("$.gender").value("MALE"));
		}

		@Test
		@DisplayName("성별이 없는 회원이 gender 를 빈 문자열로 보내면 성별을 고르라는 400 INVALID_INPUT 이다")
		void asksForGenderWhenEmptyGenderLeavesItMissing() throws Exception {
			// given: 소셜 가입 직후라 성별이 없는 회원
			logInAsGoogleMemberNamed("구글회원");

			// when & then
			updateProfile("{\"birthDate\": \"1990-01-01\", \"gender\": \"\"}")
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"))
				.andExpect(jsonPath("$.message").value("성별을 선택해주세요."));
		}

		@Test
		@DisplayName("올바른 값이면 200 이고 바뀐 프로필을 newUser=false 와 함께 돌려준다")
		void returnsUpdatedProfile() throws Exception {
			updateProfile("{\"name\": \"이훈\", \"gender\": \"FEMALE\", \"birthPlace\": \" 서울 \"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("이훈"))
				.andExpect(jsonPath("$.gender").value("FEMALE"))
				.andExpect(jsonPath("$.birthPlace").value("서울"))
				.andExpect(jsonPath("$.newUser").value(false));
		}

		private ResultActions updateProfile(String body) throws Exception {
			return mockMvc.perform(patch(PROFILE_URL).contentType(MediaType.APPLICATION_JSON).content(body));
		}

		/**
		 * 받은 이름으로 가입한 구글 회원(생년월일·성별 없음)으로 로그인한 상태를 만들고 그 회원을 돌려준다.
		 */
		private User logInAsGoogleMemberNamed(String name) {
			User googleMember = UserFixture.withId(
				User.createByOauth(GOOGLE_USERNAME, name, null, GOOGLE_USERNAME, SocialType.GOOGLE), 2L);
			given(userRepository.findByUsername(GOOGLE_USERNAME)).willReturn(Optional.of(googleMember));
			SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
				GOOGLE_USERNAME, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
			return googleMember;
		}
	}

	@Nested
	@DisplayName("결과 하나를 조회할 때")
	class WhenReadingOneResult {

		@Test
		@DisplayName("없는 사주 결과 id 이면 404 NOT_FOUND 이다")
		void unknownSajuResultIsNotFound() throws Exception {
			given(resultRepository.findById(999L)).willReturn(Optional.empty());

			mockMvc.perform(get("/api/v1/users/me/saju/999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
		}

		@Test
		@DisplayName("주인이 없는(user_id NULL) 궁합 결과이면 403 FORBIDDEN 이다")
		void compatibilityResultWithoutOwnerIsForbidden() throws Exception {
			given(compatibilityResultRepository.findById(5L)).willReturn(
				Optional.of(CompatibilityResult.createInitial(null, 10L, "궁합")));

			mockMvc.perform(get("/api/v1/users/me/compatibility/5"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.errorCode").value("FORBIDDEN"));
		}
	}
}
