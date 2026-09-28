package com.mansereok.server.domain.auth.service.oauth;

import com.mansereok.server.domain.user.entity.SocialType;
import java.util.Optional;
import org.springframework.util.StringUtils;

/**
 * 소셜 로그인 제공자가 알려 준 사용자 정보. 제공자마다 다른 응답 모양을 이 한 가지 모양으로 바꿔 로그인·가입에 쓴다.
 *
 * <p>소셜 계정은 (socialType, socialId) 쌍으로 구별한다. 둘 중 하나라도 비어 있으면 누구의 계정인지 알 수 없으므로 만들지
 * 않는다. 이메일은 사용자가 제공 동의를 하지 않으면 비어서 오므로 없을 수 있고, 공백 문자열은 null 로 바꿔 둔다.
 *
 * <p>emailTrusted 는 제공자가 그 이메일의 주인임을 확인했는지를 뜻한다. 같은 이메일의 기존 계정에 붙이거나 이메일을 저장할 때는
 * {@link #trustedEmail()} 만 쓴다.
 *
 * @param socialType   로그인한 제공자
 * @param socialId     제공자 안에서의 사용자 번호
 * @param email        제공자가 준 이메일. 없으면 null
 * @param name         표시 이름. 없으면 null
 * @param emailTrusted 제공자가 이메일 주인을 확인했으면 true
 */
public record OauthProfile(
	SocialType socialType,
	String socialId,
	String email,
	String name,
	boolean emailTrusted
) {

	public OauthProfile {
		if (socialType == null) {
			throw new IllegalArgumentException("소셜 로그인 제공자가 비어 있습니다.");
		}
		if (!StringUtils.hasText(socialId)) {
			throw new IllegalArgumentException(socialType + " 로그인 응답에 사용자 번호가 없습니다.");
		}
		if (!StringUtils.hasText(email)) {
			email = null;
		}
	}

	/**
	 * 제공자가 주인을 확인한 이메일. 이메일이 없거나 확인되지 않았으면 빈 값이다.
	 */
	public Optional<String> trustedEmail() {
		if (!emailTrusted) {
			return Optional.empty();
		}
		return Optional.ofNullable(email);
	}

	/**
	 * 로그에 이메일과 이름이 남지 않도록 제공자와 사용자 번호만 보여 준다.
	 */
	@Override
	public String toString() {
		return "OauthProfile[socialType=" + socialType + ", socialId=" + socialId + "]";
	}
}
