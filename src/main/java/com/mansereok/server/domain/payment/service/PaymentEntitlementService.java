package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 완료 건에 대한 이용 권한 확인. 해석 API 가 paymentId(PK) 를 받아 본인의 결제 완료 건인지 검사할 때 쓴다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEntitlementService {

	private final UserRepository userRepository;
	private final PaymentRepository paymentRepository;

	/**
	 * 해석 요청에 실린 paymentId(PK) 가 요청자 본인의 결제 완료 건인지 확인한다.
	 *
	 * <p>존재하지 않음 · 미결제 · 타인 소유를 모두 같은 메시지로 거부해 paymentId 열거로 상태를
	 * 알아낼 수 없게 한다.
	 */
	@Transactional(readOnly = true)
	public void verifyPaidOwnership(Long paymentPkId, String username) {
		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException("사용자를 찾을 수 없습니다."));

		Payment payment = paymentPkId == null ? null
			: paymentRepository.findById(paymentPkId).orElse(null);

		if (payment == null
			|| payment.getStatus() != PaymentStatus.PAID
			|| !Objects.equals(payment.getUserId(), user.getId())) {
			log.warn("유효하지 않은 결제로 해석 요청: username={}, paymentPkId={}, exists={}, status={}",
				username, paymentPkId, payment != null,
				payment == null ? null : payment.getStatus());
			throw new PaymentException("유효한 결제 정보가 아닙니다.");
		}
	}
}
