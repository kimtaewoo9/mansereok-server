package com.mansereok.server.domain.auth.dto.response.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 제공자 프로필 응답 객체를 로그나 문자열 연결에 넣어도 이메일·이름·별명·아이디가 나오지 않는지 확인한다.
 *
 * <p>toString 은 로그에 저절로 쓰이므로 무엇을 보여 줄지 정해 둔다. 보여 주는 것은 제공자의 사용자 번호처럼 사람을 바로 알아볼 수
 * 없는 값뿐이다.
 */
class ProviderProfileToStringTest {

	private static final String EMAIL = "leak@example.com";
	private static final String NAME = "비밀이름";

	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

	static Stream<Arguments> providerProfiles() {
		return Stream.of(
			Arguments.of("구글", GoogleProfileDto.class, "g-1", """
				{"sub": "g-1", "name": "비밀이름", "email": "leak@example.com", "email_verified": true,
				 "picture": "https://photo.example/secret.jpg"}
				"""),
			Arguments.of("카카오", KakaoProfileDto.class, "k-1", """
				{"id": "k-1", "kakao_account": {"email": "leak@example.com", "is_email_valid": true,
				 "is_email_verified": true, "profile": {"nickname": "비밀이름",
				 "profile_image_url": "https://photo.example/secret.jpg"}}}
				"""),
			Arguments.of("네이버", NaverProfileDto.class, "n-1", """
				{"resultcode": "00", "message": "success",
				 "response": {"id": "n-1", "email": "leak@example.com", "name": "비밀이름", "nickname": "비밀이름"}}
				"""),
			Arguments.of("X", XProfileResponse.class, "x-1", """
				{"data": {"id": "x-1", "name": "비밀이름", "username": "secret_handle",
				 "confirmed_email": "leak@example.com", "profile_image_url": "https://photo.example/secret.jpg"}}
				""")
		);
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("providerProfiles")
	@DisplayName("toString 은 사용자 번호만 보여 주고 이메일·이름·별명·아이디·사진 주소는 보여 주지 않는다")
	void toStringShowsOnlySocialId(String provider, Class<?> responseType, String socialId, String json)
		throws Exception {
		// given
		Object profile = objectMapper.readValue(json, responseType);

		// when
		String text = profile.toString();

		// then
		assertThat(text)
			.contains(socialId)
			.doesNotContain(EMAIL, NAME, "secret_handle", "secret.jpg");
	}
}
