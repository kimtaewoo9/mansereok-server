package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

/**
 * 환경별 yml 에 적힌 CORS 허용 출처가 SecurityConfig 의 CORS 설정에서 어떻게 판정되는지 확인한다.
 *
 * <p>yml 은 스프링이 읽는 방식(YamlPropertySourceLoader 와 Binder)으로 읽어 CorsProperties 를 만들고, 판정은
 * SecurityConfig.corsConfigurationSource() 가 만든 설정의 checkOrigin 으로 한다. checkOrigin 이 null 이면 스프링의 CorsFilter 가
 * 그 출처의 요청을 403 으로 거절하고, 브라우저는 응답을 읽지 못한다.
 */
class CorsPolicyTest {

	@Nested
	@DisplayName("운영 설정(application-prod.yml)은")
	class Production {

		private final CorsProperties properties = corsPropertiesIn("application-prod.yml");

		@Test
		@DisplayName("namedsaju.com 두 주소만 허용하고 localhost·와일드카드·vercel.app 은 적혀 있지 않다")
		void listsOnlyServiceDomains() {
			assertThat(properties.allowedOrigins())
				.containsExactlyInAnyOrder("https://namedsaju.com", "https://www.namedsaju.com")
				.allSatisfy(origin -> assertThat(origin).doesNotContain("localhost", "*", "vercel.app"));
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@ValueSource(strings = {"https://namedsaju.com", "https://www.namedsaju.com"})
		@DisplayName("서비스 주소에서 온 요청은 그 출처를 그대로 허용한다")
		void allowsServiceOrigins(String origin) {
			assertThat(corsConfigurationFor(properties).checkOrigin(origin)).isEqualTo(origin);
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@ValueSource(strings = {
			"https://evil.vercel.app",
			"https://manselab-front.vercel.app",
			"http://localhost:3000",
			"https://dev-front.namedsaju.com",
			"http://www.namedsaju.com",
			"https://www.namedsaju.com.evil.com",
			"null"
		})
		@DisplayName("목록에 없는 출처는 거절한다")
		void rejectsOtherOrigins(String origin) {
			assertThat(corsConfigurationFor(properties).checkOrigin(origin)).isNull();
		}
	}

	@Nested
	@DisplayName("개발 설정(application-dev.yml)은")
	class Development {

		private final CorsProperties properties = corsPropertiesIn("application-dev.yml");

		@Test
		@DisplayName("개발 프론트엔드, 로컬 프론트엔드, 팀의 vercel 배포 주소 하나를 허용한다")
		void listsDevelopmentFrontends() {
			assertThat(properties.allowedOrigins()).containsExactlyInAnyOrder(
				"https://dev-front.namedsaju.com",
				"http://localhost:3000",
				"https://manselab-front.vercel.app");
		}

		@ParameterizedTest(name = "[{index}] {0}")
		@ValueSource(strings = {"https://evil.vercel.app", "https://manselab-front-git-main.vercel.app"})
		@DisplayName("팀 배포 주소가 아닌 vercel.app 하위 도메인은 거절한다")
		void rejectsOtherVercelDomains(String origin) {
			assertThat(corsConfigurationFor(properties).checkOrigin(origin)).isNull();
		}
	}

	@Nested
	@DisplayName("로컬 설정(application.yml)은")
	class Local {

		@Test
		@DisplayName("로컬 프론트엔드 http://localhost:3000 하나만 허용한다")
		void listsOnlyLocalFrontend() {
			assertThat(corsPropertiesIn("application.yml").allowedOrigins())
				.containsExactly("http://localhost:3000");
		}
	}

	@Test
	@DisplayName("허용한 출처에는 쿠키를 싣는 요청을 허용한다")
	void allowsCredentials() {
		CorsConfiguration configuration = corsConfigurationFor(
			new CorsProperties(List.of("https://www.namedsaju.com")));

		assertThat(configuration.getAllowCredentials()).isTrue();
	}

	@Nested
	@DisplayName("CorsProperties 는")
	class Properties {

		@ParameterizedTest(name = "[{index}] \"{0}\"")
		@ValueSource(strings = {"*", "https://*.vercel.app", "https://www.namedsaju.com/", " "})
		@DisplayName("와일드카드, 끝의 /, 빈 값이 든 출처를 받지 않는다")
		void rejectsInexactOrigin(String origin) {
			assertThatThrownBy(() -> new CorsProperties(List.of(origin)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("허용 출처는 와일드카드나 끝의 / 없이 정확한 주소로 적어야 합니다: " + origin);
		}

		@ParameterizedTest(name = "[{index}] \"{0}\"")
		@ValueSource(strings = {"https://WWW.namedsaju.com", "HTTPS://www.namedsaju.com"})
		@DisplayName("대문자가 섞인 출처를 받지 않는다(CORS 설정과 AllowedOriginFilter 의 판정이 달라지지 않게)")
		void rejectsUppercaseOrigin(String origin) {
			assertThatThrownBy(() -> new CorsProperties(List.of(origin)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("허용 출처는 브라우저가 보내는 Origin 헤더처럼 소문자로 적어야 합니다: " + origin);
		}

		@ParameterizedTest
		@NullAndEmptySource
		@DisplayName("출처 목록이 없거나 비어 있으면 받지 않는다")
		void rejectsMissingList(List<String> origins) {
			assertThatThrownBy(() -> new CorsProperties(origins))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("app.cors.allowed-origins 에 프론트엔드 출처를 하나 이상 적어야 합니다.");
		}

		@Test
		@DisplayName("만든 뒤에 넘겨준 목록을 바꿔도 허용 출처는 바뀌지 않는다")
		void keepsItsOwnCopy() {
			// given
			List<String> origins = new ArrayList<>(List.of("https://www.namedsaju.com"));
			CorsProperties properties = new CorsProperties(origins);

			// when
			origins.add("https://evil.vercel.app");

			// then
			assertThat(properties.allowedOrigins()).containsExactly("https://www.namedsaju.com");
		}
	}

	/**
	 * 클래스패스의 yml 한 파일에서 app.cors 를 읽어 CorsProperties 로 묶는다. 다른 파일의 값은 섞지 않는다.
	 */
	private static CorsProperties corsPropertiesIn(String yamlFile) {
		try {
			List<PropertySource<?>> sources = new YamlPropertySourceLoader()
				.load(yamlFile, new ClassPathResource(yamlFile));
			return new Binder(ConfigurationPropertySources.from(sources))
				.bind("app.cors", CorsProperties.class)
				.get();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * SecurityConfig 가 모든 경로에 거는 CORS 설정을 꺼낸다. CORS 설정은 출처 목록만 쓰므로 필터와 처리기, 수집 토큰 설정은 비워 둔다.
	 */
	private static CorsConfiguration corsConfigurationFor(CorsProperties properties) {
		SecurityConfig securityConfig = new SecurityConfig(null, null, null, properties, null);
		return securityConfig.corsConfigurationSource()
			.getCorsConfiguration(new MockHttpServletRequest("POST", "/api/auth/refresh"));
	}
}
