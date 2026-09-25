package com.mansereok.server.global.exception;

import com.mansereok.server.domain.user.entity.SocialType;

/**
 * 소셜 로그인 제공자에 닿지 못했거나(연결 실패, 응답 헤더·본문 시간 초과) 제공자가 5xx·408·429 로 답해 로그인하지 못함.
 *
 * <p>사용자 잘못이 아니라 제공자 쪽 장애나 호출 한도 초과이고 잠시 뒤 다시 하면 될 수 있어 {@link OauthExceptionHandler} 가 503 으로
 * 답한다.
 *
 * <p>reason 은 운영 로그에만 쓰는 설명이고 응답에는 담지 않는다. 제공자 호출에서 난 원래 예외는 cause 로 이어 둔다.
 */
public class OauthProviderUnavailableException extends RuntimeException {

	private final SocialType provider;
	private final String reason;

	public OauthProviderUnavailableException(SocialType provider, String reason, Throwable cause) {
		super(provider + " 로그인 제공자 장애: " + reason, cause);
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
