package com.mansereok.server.domain.user.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * User 가 스스로 지키는 프로필 규칙을 스프링 없이 확인한다.
 *
 * <ul>
 *   <li>updateProfile: null 인 항목은 그대로 두고, 공백 이름은 무시하며, 태어난 장소는 앞뒤 공백을 떼어 저장한다.</li>
 *   <li>updateProfile: 바뀐 뒤 필수값(이름·생년월일·성별)이 비거나 장소가 공백뿐이면 거절하고, 이때 어떤 필드도 바뀌지 않는다.</li>
 *   <li>updateProfile: 이름 20자 한도는 이름을 다른 값으로 바꿀 때만 본다. 소셜 가입 때 받은 20자 넘는 이름을 그대로 보내면
 *   받아들인다.</li>
 *   <li>isProfileIncomplete: 이름·생년월일·성별 중 하나라도 비면 true 다.</li>
 * </ul>
 */
class UserTest {

	@Nested
	@DisplayName("프로필이 모두 채워진 회원이 프로필을 바꾸면")
	class WhenCompleteMemberUpdatesProfile {

		@Test
		@DisplayName("보낸 항목만 바뀌고 보내지 않은(null) 항목은 그대로다")
		void changesOnlyGivenFields() {
			// given: 태어난 시각과 장소까지 채운 회원
			User member = completeMember();
			member.updateProfile(new ProfileChange(null, null, LocalTime.of(7, 15), "부산", null, null));

			// when
			member.updateProfile(new ProfileChange(null, LocalDate.of(1995, 5, 5), null, null, null, true));

			// then
			assertThat(member.getName()).isEqualTo("기존이름");
			assertThat(member.getBirthDate()).isEqualTo(LocalDate.of(1995, 5, 5));
			assertThat(member.getBirthTime()).isEqualTo(LocalTime.of(7, 15));
			assertThat(member.getBirthPlace()).isEqualTo("부산");
			assertThat(member.getGender()).isEqualTo(Gender.MALE);
			assertThat(member.isMarketingAgreed()).isTrue();
		}

		@Test
		@DisplayName("다른 이름으로 바꿀 때 20자까지는 받아들인다")
		void acceptsNewNameUpToLimit() {
			// given
			User member = completeMember();

			// when
			member.updateProfile(new ProfileChange("가".repeat(20), null, null, null, null, null));

			// then
			assertThat(member.getName()).isEqualTo("가".repeat(20));
		}

		@Test
		@DisplayName("이름이 공백뿐이면 거절하지 않고 기존 이름을 그대로 두며 나머지 항목은 바꾼다")
		void keepsNameWhenNameIsBlank() {
			// given
			User member = completeMember();

			// when
			member.updateProfile(new ProfileChange("   ", null, LocalTime.of(10, 30), null, Gender.FEMALE, null));

			// then
			assertThat(member.getName()).isEqualTo("기존이름");
			assertThat(member.getBirthTime()).isEqualTo(LocalTime.of(10, 30));
			assertThat(member.getGender()).isEqualTo(Gender.FEMALE);
		}

		@Test
		@DisplayName("태어난 장소는 앞뒤 공백을 떼어 저장한다")
		void trimsBirthPlace() {
			// given
			User member = completeMember();

			// when
			member.updateProfile(new ProfileChange(null, null, null, "  서울 강남구  ", null, null));

			// then
			assertThat(member.getBirthPlace()).isEqualTo("서울 강남구");
		}
	}

	@Nested
	@DisplayName("프로필이 비어 있는 소셜 가입 회원이 프로필을 바꾸면")
	class WhenEmptySocialMemberUpdatesProfile {

		@ParameterizedTest(name = "[{index}] {0} → {2}")
		@MethodSource("rejectedChanges")
		@DisplayName("바뀐 뒤 필수값이 비거나 태어난 장소가 공백뿐이면 거절하고, 같이 보낸 다른 항목도 바꾸지 않는다")
		void rejectsAndKeepsEveryField(String description, ProfileChange change, String expectedMessage) {
			// given: 이름·생년월일·성별·장소가 모두 비어 있고 마케팅에 동의하지 않은 회원
			User member = User.createByOauth("kakao-1", null, null, "kakao-1", SocialType.KAKAO);

			// when & then
			assertThatThrownBy(() -> member.updateProfile(change))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage(expectedMessage);
			assertThat(member.getName()).as("이름").isNull();
			assertThat(member.getBirthDate()).as("생년월일").isNull();
			assertThat(member.getBirthTime()).as("태어난 시각").isNull();
			assertThat(member.getBirthPlace()).as("태어난 장소").isNull();
			assertThat(member.getGender()).as("성별").isNull();
			assertThat(member.isMarketingAgreed()).as("마케팅 동의").isFalse();
		}

		static Stream<Arguments> rejectedChanges() {
			LocalDate birthDate = LocalDate.of(1990, 1, 1);
			LocalTime birthTime = LocalTime.of(10, 30);
			return Stream.of(
				Arguments.of("이름이 공백뿐", new ProfileChange("   ", birthDate, birthTime, "서울", Gender.FEMALE, true),
					"이름을 입력해주세요."),
				Arguments.of("이름 없음", new ProfileChange(null, birthDate, birthTime, "서울", Gender.FEMALE, true),
					"이름을 입력해주세요."),
				Arguments.of("이름이 21자",
					new ProfileChange("가".repeat(21), birthDate, birthTime, "서울", Gender.FEMALE, true),
					"이름은 20자까지 입력할 수 있습니다."),
				Arguments.of("생년월일 없음", new ProfileChange("새이름", null, birthTime, "서울", Gender.FEMALE, true),
					"생년월일을 입력해주세요."),
				Arguments.of("성별 없음", new ProfileChange("새이름", birthDate, birthTime, "서울", null, true),
					"성별을 선택해주세요."),
				Arguments.of("태어난 장소가 공백뿐", new ProfileChange("새이름", birthDate, birthTime, "   ", Gender.FEMALE, true),
					"태어난 장소는 공백일 수 없습니다."));
		}

		@Test
		@DisplayName("필수값을 모두 보내면 받아들이고 추가 정보가 더는 필요 없다")
		void acceptsWhenAllRequiredFieldsAreGiven() {
			// given
			User member = User.createByOauth("kakao-1", null, null, "kakao-1", SocialType.KAKAO);

			// when
			member.updateProfile(new ProfileChange("새이름", LocalDate.of(1990, 1, 1), null, null, Gender.FEMALE, null));

			// then
			assertThat(member.getName()).isEqualTo("새이름");
			assertThat(member.getBirthDate()).isEqualTo(LocalDate.of(1990, 1, 1));
			assertThat(member.getGender()).isEqualTo(Gender.FEMALE);
			assertThat(member.isProfileIncomplete()).isFalse();
		}
	}

	@Nested
	@DisplayName("소셜 가입 때 받은 이름이 20자를 넘는 회원이 프로필을 바꾸면")
	class WhenMemberWithLongProviderNameUpdatesProfile {

		// 구글이 준 표시 이름(27자). 소셜 가입은 제공자 이름을 길이 제한 없이 저장한다.
		private static final String PROVIDER_NAME = "Christopher Alexander Smith";

		@Test
		@DisplayName("지금 이름을 그대로 담아 보내도 받아들이고 생년월일·성별을 저장한다")
		void acceptsCurrentNameLongerThanLimit() {
			// given
			User member = User.createByOauth("google-1", PROVIDER_NAME, null, "google-1", SocialType.GOOGLE);

			// when
			member.updateProfile(
				new ProfileChange(PROVIDER_NAME, LocalDate.of(1990, 1, 1), null, null, Gender.MALE, null));

			// then
			assertThat(member.getName()).isEqualTo(PROVIDER_NAME);
			assertThat(member.getBirthDate()).isEqualTo(LocalDate.of(1990, 1, 1));
			assertThat(member.getGender()).isEqualTo(Gender.MALE);
		}

		@Test
		@DisplayName("20자가 넘는 다른 이름으로 바꾸려 하면 거절하고 같이 보낸 생년월일·성별도 바꾸지 않는다")
		void rejectsDifferentNameLongerThanLimit() {
			// given
			User member = User.createByOauth("google-1", PROVIDER_NAME, null, "google-1", SocialType.GOOGLE);

			// when & then
			assertThatThrownBy(() -> member.updateProfile(new ProfileChange("Christopher A. Smith Jr.",
				LocalDate.of(1990, 1, 1), null, null, Gender.MALE, null)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("이름은 20자까지 입력할 수 있습니다.");
			assertThat(member.getName()).as("이름").isEqualTo(PROVIDER_NAME);
			assertThat(member.getBirthDate()).as("생년월일").isNull();
			assertThat(member.getGender()).as("성별").isNull();
		}
	}

	@ParameterizedTest(name = "[{index}] {0} → 추가 정보 필요 {4}")
	@MethodSource("profiles")
	@DisplayName("이름·생년월일·성별 중 하나라도 비어 있으면 추가 정보가 필요하다고 본다")
	void profileIncompleteWhenRequiredFieldIsEmpty(String description, String name, LocalDate birthDate,
		Gender gender, boolean expected) {
		// given
		User member = User.create("member@example.com", name, "encoded-password", "member@example.com", birthDate,
			gender, true, true, false);

		// when
		boolean incomplete = member.isProfileIncomplete();

		// then
		assertThat(incomplete).isEqualTo(expected);
	}

	static Stream<Arguments> profiles() {
		LocalDate birthDate = LocalDate.of(1990, 1, 1);
		return Stream.of(
			Arguments.of("모두 있음", "회원", birthDate, Gender.MALE, false),
			Arguments.of("이름 없음", null, birthDate, Gender.MALE, true),
			Arguments.of("이름이 공백뿐", "  ", birthDate, Gender.MALE, true),
			Arguments.of("생년월일 없음", "회원", null, Gender.MALE, true),
			Arguments.of("성별 없음", "회원", birthDate, null, true));
	}

	private static User completeMember() {
		return User.create("member@example.com", "기존이름", "encoded-password", "member@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false);
	}
}
