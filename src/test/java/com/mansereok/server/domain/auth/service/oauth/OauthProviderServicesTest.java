package com.mansereok.server.domain.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServiceUnavailable;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withUnauthorizedRequest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.global.exception.OauthLoginException;
import com.mansereok.server.global.exception.OauthProviderUnavailableException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 네 소셜 로그인 제공자 서비스의 authenticate 가 제공자 응답에 따라 무엇을 돌려주고 무엇을 로그에 남기는지 확인한다.
 *
 * <p>제공자는 우리가 통제하지 못하는 바깥 시스템이라 MockRestServiceServer 에 묶은 RestClient 로 흉내 낸다. 제공자가 받지 않은
 * 요청을 보내면 MockRestServiceServer 가 AssertionError 를 던지므로, "프로필을 부르지 않는다" 는 기대하지 않은 호출이 없다는 것으로
 * 확인된다.
 *
 * <p>로그는 OutputCaptureExtension 으로 콘솔 출력을 모아 본다. 앞선 테스트가 스프링을 띄워 로그 수준을 바꿔 놓았을 수 있어,
 * com.mansereok 로거를 DEBUG 로 두고 끝나면 되돌린다. 그래서 DEBUG 로그까지 확인된다.
 */
@ExtendWith(OutputCaptureExtension.class)
class OauthProviderServicesTest {

	private static final String ACCESS_TOKEN = "SECRET-ACCESS-TOKEN";
	private static final String ID_TOKEN = "SECRET-ID-TOKEN";
	private static final String REFRESH_TOKEN = "SECRET-REFRESH-TOKEN";
	private static final String EMAIL = "leak@example.com";
	private static final String NAME = "비밀이름";

	private static final String TOKEN_RESPONSE = """
		{"access_token": "SECRET-ACCESS-TOKEN", "id_token": "SECRET-ID-TOKEN",
		 "refresh_token": "SECRET-REFRESH-TOKEN", "token_type": "Bearer", "expires_in": 3600}
		""";

	private final Logger applicationLogger = (Logger) LoggerFactory.getLogger("com.mansereok");
	private Level originalLevel;

	private MockRestServiceServer providerServer;
	private RestClient restClient;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		providerServer = MockRestServiceServer.bindTo(builder).build();
		restClient = builder.build();
		originalLevel = applicationLogger.getLevel();
		applicationLogger.setLevel(Level.DEBUG);
	}

	@AfterEach
	void restoreLogLevel() {
		applicationLogger.setLevel(originalLevel);
	}

	@Nested
	@DisplayName("제공자가 토큰과 프로필을 정상으로 주면")
	class WhenProviderAnswers {

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(Provider.class)
		@DisplayName("받은 액세스 토큰으로 프로필을 읽어 제공자와 사용자 번호를 담은 OauthProfile 을 돌려준다")
		void returnsProfile(Provider provider) {
			// given
			givenTokenExchangeSucceeds(provider);
			givenProfileResponse(provider, provider.profileResponse);

			// when
			OauthProfile profile = provider.authenticate(restClient);

			// then
			assertThat(profile.socialType()).isEqualTo(provider.socialType);
			assertThat(profile.socialId()).isEqualTo(provider.socialId);
			providerServer.verify();
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(Provider.class)
		@DisplayName("로그에 토큰·이메일·이름을 남기지 않고 DEBUG 로 제공자와 사용자 번호만 남긴다")
		void logsOnlyProviderAndSocialId(Provider provider, CapturedOutput output) {
			// given
			givenTokenExchangeSucceeds(provider);
			givenProfileResponse(provider, provider.profileResponse);

			// when
			provider.authenticate(restClient);

			// then
			assertThat(output.getAll())
				.contains("provider=" + provider.socialType + ", socialId=" + provider.socialId)
				.doesNotContain(ACCESS_TOKEN, ID_TOKEN, REFRESH_TOKEN, EMAIL, NAME);
		}
	}

	@Nested
	@DisplayName("토큰 교환이 실패하면")
	class WhenTokenExchangeFails {

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(Provider.class)
		@DisplayName("제공자가 400 으로 거절하면(인가 코드 재사용 등) 프로필을 부르지 않고 OauthLoginException 을 던진다")
		void providerRejectsCode(Provider provider) {
			// given: 카카오는 이미 쓴 인가 코드에 KOE320 과 함께 코드를 되돌려 준다
			providerServer.expect(once(), requestTo(provider.tokenUrl))
				.andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON)
					.body("{\"error\": \"invalid_grant\", \"error_description\": \"authorization code not found"
						+ " for code=AUTH-CODE-123\", \"error_code\": \"KOE320\"}"));

			// when & then
			assertThatExceptionOfType(OauthLoginException.class)
				.isThrownBy(() -> provider.authenticate(restClient))
				.withCauseInstanceOf(HttpClientErrorException.class)
				.satisfies(e -> {
					assertThat(e.getProvider()).isEqualTo(provider.socialType);
					assertThat(e.getReason()).isEqualTo("토큰 교환 요청을 제공자가 거절함(HTTP 400)");
				});
			providerServer.verify();
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(Provider.class)
		@DisplayName("제공자가 503 으로 답하면 OauthProviderUnavailableException 을 던진다")
		void providerIsDown(Provider provider) {
			// given
			providerServer.expect(once(), requestTo(provider.tokenUrl))
				.andRespond(withServiceUnavailable());

			// when & then
			assertThatExceptionOfType(OauthProviderUnavailableException.class)
				.isThrownBy(() -> provider.authenticate(restClient))
				.withCauseInstanceOf(HttpServerErrorException.class)
				.satisfies(e -> {
					assertThat(e.getProvider()).isEqualTo(provider.socialType);
					assertThat(e.getReason()).isEqualTo("토큰 교환 요청에 제공자가 오류로 답함(HTTP 503)");
				});
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(Provider.class)
		@DisplayName("시간 안에 답이 없으면 OauthProviderUnavailableException 을 던지고 이유에 원인 예외 이름을 적는다")
		void providerTimesOut(Provider provider) {
			// given: RestClient 는 JDK HttpClient 의 시간 초과(IOException)를 ResourceAccessException 으로 감싼다
			providerServer.expect(once(), requestTo(provider.tokenUrl))
				.andRespond(withException(new HttpTimeoutException("request timed out")));

			// when & then
			assertThatExceptionOfType(OauthProviderUnavailableException.class)
				.isThrownBy(() -> provider.authenticate(restClient))
				.withCauseInstanceOf(ResourceAccessException.class)
				.satisfies(e -> assertThat(e.getReason())
					.isEqualTo("토큰 교환 요청을 보내지 못했거나 시간 안에 답이 없음(HttpTimeoutException)"));
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(Provider.class)
		@DisplayName("200 이어도 access_token 이 없으면(네이버의 잘못된 인가 코드 응답) 프로필을 부르지 않고 OauthLoginException 을 던진다")
		void tokenResponseWithoutAccessToken(Provider provider) {
			// given
			providerServer.expect(once(), requestTo(provider.tokenUrl))
				.andRespond(withSuccess("{\"error\": \"invalid_request\","
					+ " \"error_description\": \"no valid data in session\"}", MediaType.APPLICATION_JSON));

			// when & then
			assertThatExceptionOfType(OauthLoginException.class)
				.isThrownBy(() -> provider.authenticate(restClient))
				.satisfies(e -> assertThat(e.getReason())
					.isEqualTo("토큰 응답에 access_token 이 없음(error=invalid_request)"));
			providerServer.verify();
		}

		@Test
		@DisplayName("응답을 JSON 으로 읽지 못하면 로그인 실패로 바꾸지 않고 RestClientException 을 그대로 던진다")
		void unreadableTokenResponseIsNotTranslated() {
			// given: 우리 쪽 DTO 와 제공자 응답이 어긋난 서버 문제일 수 있어 500 으로 답하게 둔다
			providerServer.expect(once(), requestTo(Provider.GOOGLE.tokenUrl))
				.andRespond(withSuccess("{\"access_token\": \"SECRET", MediaType.APPLICATION_JSON));

			// when & then
			assertThatThrownBy(() -> Provider.GOOGLE.authenticate(restClient))
				.isInstanceOf(RestClientException.class)
				.isNotInstanceOfAny(OauthLoginException.class, OauthProviderUnavailableException.class);
		}
	}

	@Nested
	@DisplayName("프로필 조회가 실패하면")
	class WhenProfileFails {

		@ParameterizedTest(name = "[{index}] {0}")
		@EnumSource(Provider.class)
		@DisplayName("제공자가 401 로 거절하면 OauthLoginException 을 던진다")
		void providerRejectsToken(Provider provider) {
			// given
			givenTokenExchangeSucceeds(provider);
			providerServer.expect(once(), requestTo(startsWith(provider.profileUrl)))
				.andRespond(withUnauthorizedRequest());

			// when & then
			assertThatExceptionOfType(OauthLoginException.class)
				.isThrownBy(() -> provider.authenticate(restClient))
				.withCauseInstanceOf(HttpClientErrorException.class)
				.satisfies(e -> assertThat(e.getReason()).isEqualTo("프로필 조회 요청을 제공자가 거절함(HTTP 401)"));
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@CsvSource(delimiter = '|', textBlock = """
			# 제공자 | 사용자 번호가 없는 프로필 응답                                         | 로그에 남길 이유
			GOOGLE   | {"name": "비밀이름", "email": "leak@example.com"}                      | 프로필 응답에 사용자 정보가 없음
			KAKAO    | {"kakao_account": {"email": "leak@example.com"}}                      | 프로필 응답에 사용자 정보가 없음
			NAVER    | {"resultcode": "024", "message": "Authentication failed"}             | 프로필 응답에 사용자 정보가 없음
			X        | {"errors": [{"title": "Unauthorized"}]}                                | 프로필 응답 본문이 비어 있음
			""")
		@DisplayName("200 이어도 사용자 번호가 없으면 누구인지 알 수 없어 OauthLoginException 을 던진다")
		void profileWithoutSocialId(Provider provider, String profileResponse, String expectedReason) {
			// given
			givenTokenExchangeSucceeds(provider);
			givenProfileResponse(provider, profileResponse);

			// when & then
			assertThatExceptionOfType(OauthLoginException.class)
				.isThrownBy(() -> provider.authenticate(restClient))
				.satisfies(e -> assertThat(e.getReason()).isEqualTo(expectedReason));
		}
	}

	private void givenTokenExchangeSucceeds(Provider provider) {
		providerServer.expect(once(), requestTo(provider.tokenUrl))
			.andExpect(method(HttpMethod.POST))
			.andRespond(withSuccess(TOKEN_RESPONSE, MediaType.APPLICATION_JSON));
	}

	// 프로필 조회에는 토큰 교환에서 받은 액세스 토큰을 실어 보내야 한다.
	private void givenProfileResponse(Provider provider, String profileResponse) {
		providerServer.expect(once(), requestTo(startsWith(provider.profileUrl)))
			.andExpect(method(HttpMethod.GET))
			.andExpect(header("Authorization", "Bearer " + ACCESS_TOKEN))
			.andRespond(withSuccess(profileResponse, MediaType.APPLICATION_JSON));
	}

	/**
	 * 제공자마다 다른 주소·응답 모양·호출 방법을 한 줄로 모은다. 프로필 응답에는 로그에 남으면 안 되는 이메일과 이름을 넣어 둔다.
	 */
	enum Provider {
		GOOGLE(SocialType.GOOGLE, "https://oauth2.googleapis.com/token",
			"https://openidconnect.googleapis.com/v1/userinfo", "google-1", """
			{"sub": "google-1", "name": "비밀이름", "email": "leak@example.com", "email_verified": true}
			""") {
			@Override
			OauthProfile authenticate(RestClient restClient) {
				return new GoogleService(restClient).authenticate("AUTH-CODE-123");
			}
		},
		KAKAO(SocialType.KAKAO, "https://kauth.kakao.com/oauth/token", "https://kapi.kakao.com/v2/user/me",
			"kakao-1", """
			{"id": "kakao-1", "kakao_account": {"email": "leak@example.com", "is_email_valid": true,
			 "is_email_verified": true, "profile": {"nickname": "비밀이름"}}}
			""") {
			@Override
			OauthProfile authenticate(RestClient restClient) {
				return new KakaoService(restClient).authenticate("AUTH-CODE-123");
			}
		},
		NAVER(SocialType.NAVER, "https://nid.naver.com/oauth2.0/token", "https://openapi.naver.com/v1/nid/me",
			"naver-1", """
			{"resultcode": "00", "message": "success",
			 "response": {"id": "naver-1", "email": "leak@example.com", "name": "비밀이름", "nickname": "비밀이름"}}
			""") {
			@Override
			OauthProfile authenticate(RestClient restClient) {
				return new NaverService(restClient).authenticate("AUTH-CODE-123", "STATE-123");
			}
		},
		X(SocialType.X, "https://api.twitter.com/2/oauth2/token", "https://api.twitter.com/2/users/me",
			"x-1", """
			{"data": {"id": "x-1", "name": "비밀이름", "username": "secret_handle",
			 "confirmed_email": "leak@example.com"}}
			""") {
			@Override
			OauthProfile authenticate(RestClient restClient) {
				return new XService(restClient).authenticate("AUTH-CODE-123", "CODE-VERIFIER-123");
			}
		};

		private final SocialType socialType;
		private final String tokenUrl;
		private final String profileUrl;
		private final String socialId;
		private final String profileResponse;

		Provider(SocialType socialType, String tokenUrl, String profileUrl, String socialId,
			String profileResponse) {
			this.socialType = socialType;
			this.tokenUrl = tokenUrl;
			this.profileUrl = profileUrl;
			this.socialId = socialId;
			this.profileResponse = profileResponse;
		}

		abstract OauthProfile authenticate(RestClient restClient);
	}
}
