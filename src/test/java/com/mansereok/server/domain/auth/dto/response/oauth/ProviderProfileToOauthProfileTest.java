package com.mansereok.server.domain.auth.dto.response.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.auth.service.oauth.OauthProfile;
import com.mansereok.server.domain.user.entity.SocialType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 제공자가 돌려준 프로필 JSON 을 읽어 OauthProfile 로 바꾸는 규칙을 확인한다. 특히 어떤 이메일을 신뢰하는지 본다.
 *
 * <p>JSON 은 RestClient 가 쓰는 것과 같은 설정의 ObjectMapper 로 읽는다.
 */
class ProviderProfileToOauthProfileTest {

	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

	@Nested
	@DisplayName("카카오")
	class Kakao {

		@ParameterizedTest(name = "[{index}] is_email_valid={0}, is_email_verified={1} → 신뢰하는 이메일 [{2}]")
		@CsvSource(nullValues = "NULL", textBlock = """
			# is_email_valid, is_email_verified, 신뢰하는 이메일
			true,             true,              a@kakao.com
			true,             false,             NULL
			false,            true,              NULL
			false,            false,             NULL
			""")
		@DisplayName("이메일이 유효하고 카카오가 확인까지 한 경우에만 이메일을 신뢰한다")
		void trustsEmailOnlyWhenValidAndVerified(boolean emailValid, boolean emailVerified,
			String expectedTrustedEmail) throws Exception {
			// given
			String json = """
				{"id": 123456789,
				 "kakao_account": {"email": "a@kakao.com", "is_email_valid": %s, "is_email_verified": %s,
				                   "profile": {"nickname": "라이언"}}}
				""".formatted(emailValid, emailVerified);

			// when
			OauthProfile profile = objectMapper.readValue(json, KakaoProfileDto.class).toOauthProfile();

			// then
			assertThat(profile.trustedEmail().orElse(null)).isEqualTo(expectedTrustedEmail);
		}

		@Test
		@DisplayName("이메일 인증 값이 오지 않으면 이메일을 신뢰하지 않는다")
		void doesNotTrustEmailWithoutVerificationFields() throws Exception {
			String json = """
				{"id": 123456789, "kakao_account": {"email": "a@kakao.com"}}
				""";

			OauthProfile profile = objectMapper.readValue(json, KakaoProfileDto.class).toOauthProfile();

			assertThat(profile.trustedEmail()).isEmpty();
		}

		@Test
		@DisplayName("kakao_account 가 통째로 없으면 오류 없이 이메일 없는 프로필이 된다")
		void readsProfileWithoutKakaoAccount() throws Exception {
			String json = """
				{"id": 123456789}
				""";

			OauthProfile profile = objectMapper.readValue(json, KakaoProfileDto.class).toOauthProfile();

			assertThat(profile).extracting(OauthProfile::socialType, OauthProfile::socialId,
					OauthProfile::email, OauthProfile::name)
				.containsExactly(SocialType.KAKAO, "123456789", null, "카카오 사용자");
		}
	}

	@Nested
	@DisplayName("구글")
	class Google {

		@ParameterizedTest(name = "[{index}] email_verified={0} → 신뢰하는 이메일 [{1}]")
		@CsvSource(nullValues = "NULL", textBlock = """
			# email_verified, 신뢰하는 이메일
			true,             g@gmail.com
			false,            NULL
			""")
		@DisplayName("email_verified 가 true 일 때만 이메일을 신뢰한다")
		void trustsEmailOnlyWhenVerified(boolean emailVerified, String expectedTrustedEmail)
			throws Exception {
			// given
			String json = """
				{"sub": "109876543210", "name": "구글 사용자", "email": "g@gmail.com", "email_verified": %s}
				""".formatted(emailVerified);

			// when
			OauthProfile profile = objectMapper.readValue(json, GoogleProfileDto.class).toOauthProfile();

			// then
			assertThat(profile.trustedEmail().orElse(null)).isEqualTo(expectedTrustedEmail);
		}

		@Test
		@DisplayName("email_verified 가 오지 않으면 이메일을 신뢰하지 않는다")
		void doesNotTrustEmailWithoutVerifiedField() throws Exception {
			String json = """
				{"sub": "109876543210", "name": "구글 사용자", "email": "g@gmail.com"}
				""";

			OauthProfile profile = objectMapper.readValue(json, GoogleProfileDto.class).toOauthProfile();

			assertThat(profile.trustedEmail()).isEmpty();
		}
	}

	@Nested
	@DisplayName("네이버")
	class Naver {

		@Test
		@DisplayName("인증 여부를 주지 않지만 지금처럼 네이버 이메일을 신뢰한다")
		void trustsNaverEmail() throws Exception {
			String json = """
				{"resultcode": "00", "message": "success",
				 "response": {"id": "naver-abc", "email": "n@naver.com", "name": "네이버 사용자"}}
				""";

			OauthProfile profile = objectMapper.readValue(json, NaverProfileDto.class).toOauthProfile();

			assertThat(profile).extracting(OauthProfile::socialType, OauthProfile::socialId,
					OauthProfile::name)
				.containsExactly(SocialType.NAVER, "naver-abc", "네이버 사용자");
			assertThat(profile.trustedEmail()).contains("n@naver.com");
		}

		@Test
		@DisplayName("사용자 정보(response)가 없으면 로그인 요청을 거절한다")
		void rejectsResponseWithoutUser() throws Exception {
			String json = """
				{"resultcode": "024", "message": "Authentication failed"}
				""";
			NaverProfileDto naverProfileDto = objectMapper.readValue(json, NaverProfileDto.class);

			assertThatThrownBy(naverProfileDto::toOauthProfile)
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("NAVER 로그인 응답에 사용자 정보가 없습니다.");
		}
	}

	@Nested
	@DisplayName("X")
	class X {

		@Test
		@DisplayName("confirmed_email 은 이메일로, 표시 이름(name)은 이름으로 읽는다")
		void readsConfirmedEmailAsEmailAndDisplayNameAsName() throws Exception {
			String json = """
				{"data": {"id": "x1", "name": "민지", "username": "minji_x", "confirmed_email": "minji@x.com"}}
				""";

			OauthProfile profile = objectMapper.readValue(json, XProfileResponse.class).getData()
				.toOauthProfile();

			assertThat(profile).extracting(OauthProfile::socialType, OauthProfile::socialId,
					OauthProfile::name)
				.containsExactly(SocialType.X, "x1", "민지");
			assertThat(profile.trustedEmail()).contains("minji@x.com");
		}

		@Test
		@DisplayName("confirmed_email 이 없으면 이메일 없는 프로필이 된다")
		void readsProfileWithoutConfirmedEmail() throws Exception {
			String json = """
				{"data": {"id": "x1", "name": "민지", "username": "minji_x"}}
				""";

			OauthProfile profile = objectMapper.readValue(json, XProfileResponse.class).getData()
				.toOauthProfile();

			assertThat(profile.email()).isNull();
			assertThat(profile.name()).isEqualTo("민지");
		}
	}
}
