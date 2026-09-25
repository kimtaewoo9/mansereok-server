package com.mansereok.server.global.exception;

import com.mansereok.server.domain.user.entity.SocialType;

/**
 * 소셜 로그인 제공자가 요청을 거절했거나(4xx) 로그인에 필요한 값(액세스 토큰, 사용자 번호)을 주지 않아 로그인하지 못함.
 *
 * <p>같은 인가 코드를 두 번 보냈거나(콜백 페이지 새로 고침, 버튼 두 번 누르기), 코드가 만료됐거나, 사용자가 동의를 거둔 경우가
 * 대부분이다. 서버 장애가 아니라 처음부터 다시 로그인하면 되는 실패라 {@link OauthExceptionHandler} 가 401 로 답한다.
 *
 * <p>reason 은 운영 로그에만 쓰는 설명이고 응답에는 담지 않는다. 토큰·이메일·이름을 넣지 않는다. 제공자 호출에서 난 원래 예외는
 * cause 로 이어 둔다.
 */
public class OauthLoginException extends RuntimeException {

	private final SocialType provider;
	private final String reason;

	public OauthLoginException(SocialType provider, String reason) {
		this(provider, reason, null);
	}

	public OauthLoginException(SocialType provider, String reason, Throwable cause) {
		super(provider + " 로그인 실패: " + reason, cause);
		this.provider = provider;
		this.reason = reason;
	}

	public SocialType getProvider() {
		return provider;
	}

	public String getReason() {
		return reason;
	}
}
