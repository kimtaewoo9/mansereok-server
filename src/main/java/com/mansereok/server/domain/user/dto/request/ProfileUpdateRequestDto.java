package com.mansereok.server.domain.user.dto.request;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.ProfileChange;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 프로필 수정 요청. 보내지 않은(null) 항목은 바꾸지 않는다. 필수값, 이름 길이, 태어난 장소 규칙은 User.updateProfile 이 검사한다.
 */
@Getter
@Setter
public class ProfileUpdateRequestDto {

	private String name;
	private LocalDate birthDate;
	@JsonFormat(pattern = "HH:mm")
	private LocalTime birthTime;
	private String birthPlace;
	// 열거 상수 이름("MALE", "FEMALE")만 받는다. 다른 값("M", "male")은 본문을 읽을 때 400 INVALID_REQUEST_BODY 가 된다.
	// 빈 문자열("")이나 공백뿐인 값은 보내지 않은 것으로 보고 성별을 바꾸지 않는다.
	@JsonDeserialize(using = BlankGenderAsNotSentDeserializer.class)
	private Gender gender;

	private Boolean marketingAgreed;

	public ProfileChange toProfileChange() {
		return new ProfileChange(name, birthDate, birthTime, birthPlace, gender, marketingAgreed);
	}

	/**
	 * gender 가 빈 문자열이거나 공백뿐이면 null 로 읽는다. 그 밖의 값은 Jackson 의 기본 열거 타입 읽기에 넘겨, 잘못된 값이면 보낸 값을
	 * 담은 InvalidFormatException(400 INVALID_REQUEST_BODY)이 되는 것은 그대로 둔다.
	 */
	static final class BlankGenderAsNotSentDeserializer extends StdDeserializer<Gender> {

		BlankGenderAsNotSentDeserializer() {
			super(Gender.class);
		}

		@Override
		public Gender deserialize(JsonParser parser, DeserializationContext context) throws IOException {
			if (parser.hasToken(JsonToken.VALUE_STRING) && parser.getText().isBlank()) {
				return null;
			}
			return (Gender) context.findRootValueDeserializer(context.constructType(Gender.class))
				.deserialize(parser, context);
		}
	}
}
