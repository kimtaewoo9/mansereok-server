package com.mansereok.server.domain.payment.service;

import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.PaymentException;
import java.util.Objects;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 완료 건으로 해석을 시작해도 되는지 확인한다. 해석 API 가 paymentId(PK) 를 받아 해석을 시작할 때 쓴다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentEntitlementService {

	private final UserRepository userRepository;
	private final PaymentRepository paymentRepository;

	/**
	 * 결제 행을 잠근 채 해석을 시작해도 되는지 확인하고, 같은 트랜잭션에서 결과를 해석 중으로 바꾼다.
	 *
	 * <p>허용 조건은 셋이다. 결제가 PAID 이고, 요청자 본인의 결제이고, 결제한 상품이 해석하려는 상품(경로의 subCategoryId)과
	 * 같아야 한다. 존재하지 않음 · 미결제 · 타인 소유 · 다른 상품을 모두 같은 메시지로 거부해 paymentId 열거로 상태를 알아낼 수
	 * 없게 한다. 어긋난 조건은 로그에만 남긴다.
	 *
	 * <p>확인과 결과 변경을 결제 행 잠금 아래 한 트랜잭션에서 한다. 환불(PaymentRefundService 의 트랜잭션 A)도 같은 결제 행을
	 * 먼저 잠그므로 둘은 이 행에서 줄을 선다. 해석 시작이 먼저면 환불은 해석 중인 결과를 보고 거부되고, 환불이 먼저면 해석 시작은
	 * CANCEL_REQUESTED 를 보고 거부된다. 조건이 어긋나면 결과를 바꾸기 전에 거부한다.
	 *
	 * <p>결과를 바꾸는 일은 호출자가 넘긴다(일반 사주와 궁합의 결과가 다르다). 이 서비스는 결과 쪽 메서드 이름에 기대지 않는다.
	 *
	 * @param paymentPkId          해석에 쓸 결제의 PK. null 이면 거부한다.
	 * @param username             요청자
	 * @param subCategoryId        해석하려는 상품. 결제한 상품과 다르면 거부한다.
	 * @param markResultProcessing 허용될 때만 결제 PK 로 한 번 부르는, 결과를 해석 중으로 바꾸는 일. 이 트랜잭션 안에서 돈다.
	 * @throws PaymentException 사용자가 없거나, 결제가 위 조건을 하나라도 어길 때
	 */
	@Transactional
	public void startInterpretation(Long paymentPkId, String username, Long subCategoryId,
		Consumer<Long> markResultProcessing) {
		Objects.requireNonNull(markResultProcessing, "markResultProcessing");

		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException("사용자를 찾을 수 없습니다."));

		Payment payment = paymentPkId == null ? null
			: paymentRepository.findByIdWithLock(paymentPkId).orElse(null);

		String unmetCondition = findUnmetCondition(payment, user.getId(), subCategoryId);
		if (unmetCondition != null) {
			log.warn("유효하지 않은 결제로 해석 요청: username={}, paymentPkId={}, subCategoryId={}, 어긋난 조건={}",
				username, paymentPkId, subCategoryId, unmetCondition);
			throw new PaymentException("유효한 결제 정보가 아닙니다.");
		}

		markResultProcessing.accept(paymentPkId);
	}

	/**
	 * 해석을 허용할 수 없는 이유를 돌려준다. 모든 조건을 지키면 null 이다.
	 */
	private static String findUnmetCondition(Payment payment, Long userId, Long subCategoryId) {
		if (payment == null) {
			return "결제 없음";
		}
		if (payment.getStatus() != PaymentStatus.PAID) {
			return "결제 상태 " + payment.getStatus();
		}
		// 탈퇴 처리로 결제의 userId 가 비었으면 누구의 결제도 아니다.
		if (payment.getUserId() == null || !payment.getUserId().equals(userId)) {
			return "다른 사용자의 결제(결제 userId=" + payment.getUserId() + ")";
		}
		if (subCategoryId == null || !subCategoryId.equals(payment.getSubCategoryId())) {
			return "결제한 상품과 다름(결제 subCategoryId=" + payment.getSubCategoryId() + ")";
		}
		return null;
	}
}
