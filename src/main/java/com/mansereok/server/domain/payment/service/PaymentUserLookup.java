package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 결제·쿠폰 API 가 요청자(로그인한 username)를 사용자로 바꾸는 한 곳.
 *
 * <p>JWT 필터는 DB 에서 사용자를 확인하지 않아, 탈퇴 직후 남은 토큰으로 요청하면 여기서 사용자가 없다. 그때 결제·쿠폰 API 는
 * 모두 같은 PaymentException("사용자를 찾을 수 없습니다."), 곧 400 PAYMENT_ERROR 와 같은 문구로 답한다. PAYMENT_ERROR 는 다른
 * 결제 업무 오류도 함께 쓰는 코드라 사용자 없음은 문구로만 구별된다. UserService.findByUsername 을 쓰는 프로필·리뷰 API 는
 * EntityNotFoundException 을 거쳐 404 NOT_FOUND 로 답하므로, 앱 전체로 보면 사용자 없음 응답은 아직 두 가지다.
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
