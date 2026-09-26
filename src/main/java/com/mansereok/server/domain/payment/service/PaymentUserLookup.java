package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 결제·쿠폰 API 가 요청자(로그인한 username)를 사용자로 바꾸는 한 곳.
 *
 * <p>JWT 필터는 DB 에서 사용자를 확인하지 않아, 탈퇴 직후 남은 토큰으로 요청하면 여기서 사용자가 없다. 그때 어느 API 든 같은
 * PaymentException("사용자를 찾을 수 없습니다.")(400 PAYMENT_ERROR)으로 답해, 프론트가 한 가지 처리로 다시 로그인하게 할 수 있다.
 */
@Component
@RequiredArgsConstructor
public class PaymentUserLookup {

	static final String USER_NOT_FOUND_MESSAGE = "사용자를 찾을 수 없습니다.";

	private final UserRepository userRepository;

	/**
	 * @throws PaymentException 그 username 의 사용자가 없을 때
	 */
	public User getByUsername(String username) {
		return userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException(USER_NOT_FOUND_MESSAGE));
	}
}
