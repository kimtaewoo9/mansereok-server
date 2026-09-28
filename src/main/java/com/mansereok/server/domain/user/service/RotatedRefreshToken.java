package com.mansereok.server.domain.user.service;

import com.mansereok.server.domain.user.entity.User;

/**
 * 재발급 결과. token 은 쿠키로 내려줄 새 리프레시 토큰이고, user 는 새 액세스 토큰과 응답을 만들 회원이다.
 *
 * <p>user 는 재발급 트랜잭션 안에서 다 읽어 둔 것이라 트랜잭션 밖에서 값을 꺼내도 지연 로딩이 일어나지 않는다. 그래서 컨트롤러는
 * open-in-view 에 기대지 않는다.
 */
public record RotatedRefreshToken(String token, User user) {

}
