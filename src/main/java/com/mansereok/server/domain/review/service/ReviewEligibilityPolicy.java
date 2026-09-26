package com.mansereok.server.domain.review.service;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * 주문 하나로 리뷰를 쓸 수 있는지 판단한다. 자격 조회 API 와 작성 API 가 모두 이 판단을 쓰므로, 규칙을 바꿀 때는 여기만 고친다.
 *
 * <p>규칙은 아래 순서로 보고 처음 걸린 규칙의 이유를 돌려준다.
 * <ol>
 *   <li>요청한 회원의 주문인가. 탈퇴한 회원의 주문은 user_id 가 비어 있어 누구의 주문도 아니다.</li>
 *   <li>리뷰하려는 상품을 산 주문인가.</li>
 *   <li>결제 완료(PAID) 상태인가. 환불하면 상태는 CANCELLED 로 바뀌지만 결제 시각(paidAt)은 지우지 않으므로, 결제 시각이 아니라
 *   상태로 본다.</li>
 *   <li>결제한 날부터 {@value #REVIEW_DEADLINE_DAYS}일이 지나지 않았는가. 결제한 날을 0일로 세어 {@value #REVIEW_DEADLINE_DAYS}일째
 *   되는 날까지 쓸 수 있다.</li>
 *   <li>이 주문으로 쓴 리뷰가 없는가. DB 를 조회해야 하므로 앞 규칙을 모두 통과했을 때만 묻는다.</li>
 * </ol>
 *
 * <p>주문을 찾지 못한 경우는 부르는 쪽이 {@link RejectionReason#ORDER_NOT_FOUND} 로 답한다.
 */
public final class ReviewEligibilityPolicy {

	/** 결제한 날부터 리뷰를 쓸 수 있는 날 수. */
	public static final int REVIEW_DEADLINE_DAYS = 30;

	private ReviewEligibilityPolicy() {
	}

	/**
	 * 주문으로 리뷰를 쓸 수 없는 이유를 찾는다.
	 *
	 * @param order          리뷰를 쓰려는 주문
	 * @param requesterId    요청한 회원 id. null 이 아니다.
	 * @param subCategoryId  리뷰하려는 상품 id. null 이 아니다.
	 * @param today          오늘 날짜. 부르는 쪽이 주입받은 Clock 으로 구한다.
	 * @param alreadyWritten 이 주문으로 쓴 리뷰가 있는지 묻는 조회. 앞 규칙을 모두 통과했을 때만 부른다.
	 * @return 쓸 수 없으면 그 이유, 쓸 수 있으면 빈 값
	 */
	public static Optional<RejectionReason> findRejection(Order order, Long requesterId, Long subCategoryId,
		LocalDate today, BooleanSupplier alreadyWritten) {
		// 탈퇴한 회원의 주문은 userId 가 null 이라 null 이 아닌 요청자 id 쪽에서 비교한다.
		if (!requesterId.equals(order.getUserId())) {
			return Optional.of(RejectionReason.NOT_OWNER);
		}
		if (!subCategoryId.equals(order.getSubCategoryId())) {
			return Optional.of(RejectionReason.MISMATCH_PRODUCT);
		}
		// PAID 로 바꾸는 코드는 모두 결제 시각도 함께 채운다. 결제 시각이 비어 있으면 기한을 셀 수 없어 결제 전 주문과 같게 거절한다.
		if (order.getStatus() != OrderStatus.PAID || order.getPaidAt() == null) {
			return Optional.of(RejectionReason.NOT_PAID);
		}
		if (ChronoUnit.DAYS.between(order.getPaidAt().toLocalDate(), today) > REVIEW_DEADLINE_DAYS) {
			return Optional.of(RejectionReason.EXPIRED);
		}
		if (alreadyWritten.getAsBoolean()) {
			return Optional.of(RejectionReason.ALREADY_WRITTEN);
		}
		return Optional.empty();
	}
}
