package com.mansereok.server.domain.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.user.entity.SocialType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class OauthProfileTest {

	@Nested
	@DisplayName("누구의 소셜 계정인지 알 수 없으면")
	class WhenOwnerUnknown {

		@ParameterizedTest(name = "[{index}] 사용자 번호 [{0}]")
		@NullAndEmptySource
		@ValueSource(strings = {"   "})
		@DisplayName("사용자 번호가 비어 있으면 만들지 않는다")
		void rejectsBlankSocialId(String socialId) {
			assertThatThrownBy(() -> new OauthProfile(SocialType.KAKAO, socialId, null, "이름", false))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("KAKAO 로그인 응답에 사용자 번호가 없습니다.");
		}

		@Test
		@DisplayName("제공자가 비어 있으면 만들지 않는다")
		void rejectsMissingSocialType() {
			assertThatThrownBy(() -> new OauthProfile(null, "100", null, "이름", false))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("소셜 로그인 제공자가 비어 있습니다.");
		}
	}

	@ParameterizedTest(name = "[{index}] 이메일 [{0}]")
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\t"})
	@DisplayName("이메일이 null 이거나 공백이면 이메일 없음(null)으로 둔다")
	void blankEmailBecomesNull(String email) {
		// when
		OauthProfile profile = new OauthProfile(SocialType.KAKAO, "100", email, "이름", true);

		// then
		assertThat(profile.email()).isNull();
	}

	@ParameterizedTest(name = "[{index}] 이메일 [{0}], 제공자 확인 {1} → 신뢰하는 이메일 [{2}]")
	@CsvSource(nullValues = "NULL", textBlock = """
		# 이메일,         제공자 확인, 신뢰하는 이메일
		a@example.com,    true,        a@example.com
		a@example.com,    false,       NULL
		NULL,             true,        NULL
		'  ',             true,        NULL
		""")
	@DisplayName("제공자가 주인을 확인한 이메일만 신뢰하는 이메일로 돌려준다")
	void trustedEmailOnlyWhenProviderConfirmed(String email, boolean emailTrusted,
		String expectedTrustedEmail) {
		// given
		OauthProfile profile = new OauthProfile(SocialType.GOOGLE, "100", email, "이름", emailTrusted);

		// when & then
		assertThat(profile.trustedEmail().orElse(null)).isEqualTo(expectedTrustedEmail);
	}

	@Test
	@DisplayName("문자열로 바꿔도 이메일과 이름이 드러나지 않는다")
	void toStringHidesEmailAndName() {
		OauthProfile profile = new OauthProfile(SocialType.X, "x1", "minji@x.com", "민지", true);

		assertThat(profile.toString())
			.isEqualTo("OauthProfile[socialType=X, socialId=x1]");
	}
}
