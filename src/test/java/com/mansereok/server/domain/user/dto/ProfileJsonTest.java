package com.mansereok.server.domain.user.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.mansereok.server.domain.user.dto.request.ProfileUpdateRequestDto;
import com.mansereok.server.domain.user.dto.response.ProfileResponseDto;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * 프로필 API 의 JSON 모양을 프론트와 맞춘 대로 고정한다. 운영의 스프링 부트와 같은 기본값의 ObjectMapper 로 읽고 쓴다.
 *
 * <ul>
 *   <li>수정 요청의 gender 는 열거 상수 이름("MALE", "FEMALE")만 읽는다. 읽지 못한 값은 RequestErrorExceptionHandler 가 400 으로
 *   답한다(ProfileControllerRequestTest). 빈 문자열이나 공백뿐인 값은 성별을 바꾸지 않는 null 로 읽는다.</li>
 *   <li>응답의 추가 정보 필요 여부는 예전과 같은 키 newUser 로 나간다.</li>
 * </ul>
 */
class ProfileJsonTest {

	// 스프링 부트의 ObjectMapper 처럼 날짜를 숫자 배열이 아닌 ISO 문자열로 쓴다. 모르는 속성 무시는 이 빌더의 기본값이다.
	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json()
		.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
		.build();

	@ParameterizedTest(name = "[{index}] gender \"{0}\" → {1}")
	@CsvSource(textBlock = """
		# 보낸 값, 읽은 성별
		MALE,   MALE
		FEMALE, FEMALE
		""")
	@DisplayName("수정 요청의 gender 가 열거 상수 이름이면 그 성별로 읽는다")
	void readsGenderByConstantName(String sent, Gender expected) throws Exception {
		// when
		ProfileUpdateRequestDto request = objectMapper.readValue("{\"gender\": \"" + sent + "\"}",
			ProfileUpdateRequestDto.class);

		// then
		assertThat(request.getGender()).isEqualTo(expected);
	}

	@ParameterizedTest(name = "[{index}] gender \"{0}\" → 읽지 못함")
	@ValueSource(strings = {"M", "male", "여자"})
	@DisplayName("수정 요청의 gender 가 성별 코드·소문자·한글이면 읽지 못하고, 예외에 보낸 값이 담긴다")
	void rejectsGenderOtherThanConstantName(String sent) {
		// when & then
		assertThatThrownBy(() -> objectMapper.readValue("{\"gender\": \"" + sent + "\"}",
			ProfileUpdateRequestDto.class))
			.isInstanceOf(InvalidFormatException.class)
			.extracting(e -> ((InvalidFormatException) e).getValue())
			.isEqualTo(sent);
	}

	@Test
	@DisplayName("수정 요청에 gender 가 없거나 null 이면 성별을 바꾸지 않는 null 로 읽는다")
	void readsMissingGenderAsNull() throws Exception {
		// when
		ProfileUpdateRequestDto missing = objectMapper.readValue("{}", ProfileUpdateRequestDto.class);
		ProfileUpdateRequestDto explicitNull = objectMapper.readValue("{\"gender\": null}",
			ProfileUpdateRequestDto.class);

		// then
		assertThat(missing.getGender()).isNull();
		assertThat(explicitNull.getGender()).isNull();
	}

	@ParameterizedTest(name = "[{index}] gender \"{0}\" → null")
	@ValueSource(strings = {"", "   "})
	@DisplayName("수정 요청의 gender 가 빈 문자열이거나 공백뿐이면 오류 없이 성별을 바꾸지 않는 null 로 읽는다")
	void readsBlankGenderAsNull(String sent) throws Exception {
		// when
		ProfileUpdateRequestDto request = objectMapper.readValue("{\"gender\": \"" + sent + "\"}",
			ProfileUpdateRequestDto.class);

		// then
		assertThat(request.getGender()).isNull();
	}

	@Test
	@DisplayName("생년월일이 빈 소셜 가입 회원의 프로필 응답은 newUser 키에 true 를 담고, 다른 이름의 키는 없다")
	void writesProfileIncompleteAsNewUserKey() throws Exception {
		// given
		User member = User.createByOauth("kakao-1", "소셜회원", null, "kakao-1", SocialType.KAKAO);

		// when
		JsonNode json = writeAsJson(new ProfileResponseDto(member));

		// then
		assertThat(json.get("newUser").asBoolean()).isTrue();
		assertThat(json.has("isNewUser")).isFalse();
		assertThat(json.has("profileIncomplete")).isFalse();
	}

	@Test
	@DisplayName("이름·생년월일·성별이 모두 있는 회원의 프로필 응답은 newUser 키에 false 를 담는다")
	void writesCompleteProfileAsNotNewUser() throws Exception {
		// given
		User member = User.create("member@example.com", "회원", "encoded-password", "member@example.com",
			LocalDate.of(1990, 1, 1), Gender.FEMALE, true, true, false);

		// when
		JsonNode json = writeAsJson(new ProfileResponseDto(member));

		// then
		assertThat(json.get("newUser").asBoolean()).isFalse();
		assertThat(json.get("gender").asText()).isEqualTo("FEMALE");
	}

	/** HTTP 응답 본문과 같게 문자열로 쓴 뒤 다시 읽는다. */
	private JsonNode writeAsJson(Object response) throws Exception {
		return objectMapper.readTree(objectMapper.writeValueAsString(response));
	}
}
