package com.mansereok.server.domain.auth.service.oauth;

import com.mansereok.server.domain.user.entity.User;

/**
 * 소셜 로그인 결과.
 *
 * @param user            로그인한 계정
 * @param newlyRegistered 이번 로그인에서 새로 가입했으면 true
 */
public record OauthLoginResult(User user, boolean newlyRegistered) {

}
