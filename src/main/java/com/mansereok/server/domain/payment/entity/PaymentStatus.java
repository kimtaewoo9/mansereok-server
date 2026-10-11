package com.mansereok.server.domain.payment.entity;

import java.util.Locale;
import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PaymentStatus {
	PAID("결제완료"),
	VIRTUAL_ACCOUNT_ISSUED("가상계좌 발급"),
	FAILED("결제실패"),
	CANCELLED("결제취소"),
	/** 환불 진행 중. 포트원 취소 API 를 부르기 전에 DB 에 남기는 흔적이라 포트원 상태값과 대응하지 않는다. */
	CANCEL_REQUESTED("취소 요청됨"),
	READY("미결제"); // 결제 진행중 .

	private final String description;

	/**
	 * 포트원 V2 결제 조회 API 의 status 문자열을 도메인 상태로 매핑한다 (대소문자 무시). 매핑의 단일 소스는
	 * 이 switch 다.
	 *
	 * <ul>
	 *   <li>PAID, READY, VIRTUAL_ACCOUNT_ISSUED, FAILED, CANCELLED → 같은 이름의 상수</li>
	 *   <li>PAY_PENDING → READY (승인 대기, 아직 완료 아님)</li>
	 *   <li>PARTIAL_CANCELLED → CANCELLED</li>
	 * </ul>
	 *
	 * <p>CANCEL_REQUESTED 는 이 서버가 환불 도중 기록하는 내부 상태라 포트원 상태값에서 매핑되지 않는다.
	 *
	 * <p>모르는 값과 null 은 실패로 접지 않고 빈 Optional 을 돌려준다. 호출자는 "아직 완료되지 않음" 으로
	 * 다뤄야 하며, 주문을 FAILED 로 바꾸거나 예외를 던지면 안 된다.
	 */
	public static Optional<PaymentStatus> fromPortOneStatus(String status) {
		if (status == null) {
			return Optional.empty();
		}
		return switch (status.trim().toUpperCase(Locale.ROOT)) {
			case "PAID" -> Optional.of(PAID);
			case "READY", "PAY_PENDING" -> Optional.of(READY);
			case "VIRTUAL_ACCOUNT_ISSUED" -> Optional.of(VIRTUAL_ACCOUNT_ISSUED);
			case "FAILED" -> Optional.of(FAILED);
			case "CANCELLED", "PARTIAL_CANCELLED" -> Optional.of(CANCELLED);
			default -> Optional.empty();
		};
	}
}
