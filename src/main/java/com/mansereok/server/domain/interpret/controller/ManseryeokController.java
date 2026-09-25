package com.mansereok.server.domain.interpret.controller;

import com.mansereok.server.domain.interpret.dto.request.CompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseCompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseInterpretationRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCalculationRequest;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.service.ManseCalculationService;
import com.mansereok.server.domain.interpret.service.ManseInterpretationService;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.service.PaymentService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ManseryeokController {

	private final ManseCalculationService manseCalculationService;
	private final ManseInterpretationService manseInterpretationService;

	private final PaymentService paymentService;
	private final ResultService resultService;

	@PostMapping("/api/v1/manseryeok/calculate")
	public ResponseEntity<ManseryeokCalculationResponse> calculate(
		@Valid @RequestBody ManseryeokCalculationRequest request
	) {
		log.info("만세력 계산 요청: solarDate={}, gender={}, isLunar={}",
			request.getSolarDate(), request.getGender(), request.getIsLunar());
		ManseryeokCalculationResponse response = manseCalculationService.calculate(request);
		log.info("만세력 계산 완료: daySky={}", response.getSaju().getDaySky().getChinese());

		return ResponseEntity.ok(response);
	}

	@PostMapping("/api/v1/manseryeok/interpret/{subcategoryId}")
	public ResponseEntity<?> interpret(
		@PathVariable Long subcategoryId,
		@Valid @RequestBody ManseInterpretationRequest request,
		@AuthenticationPrincipal String username
	) {
		log.info("만세력 해석 요청 username: " + username);

		// 1. 만세력 계산 (공통). DB 를 읽기만 하므로 입력이 잘못돼 실패해도 결과 상태는 그대로다.
		ManseryeokCalculationResponse manse = manseCalculationService.calculate(
				new ManseryeokCalculationRequest(
					request.getName(),
					request.getSolarDate(),
					request.getSolarTime(),
					request.getGender(),
					request.getIsLunar(),
					request.getLeapMonth()
				)
			);

		// 2. 해석 시작 표시 (공통). 이미 해석 중이거나 완료된 결제면 409 로 끝나고 해석을 제출하지 않는다.
		resultService.startProcessing(request.getPaymentId());

		// 3. 비동기 해석 제출
		submitOrRollback(request.getPaymentId(), resultService::rollbackStatusByPaymentId, () -> {
			if (subcategoryId >= 100) {
				manseInterpretationService.interpretFree(
					request.getName(),
					manse,
					username,
					subcategoryId,
					request.getPaymentId()
				);
			} else {
				manseInterpretationService.interpret(
					request.getName(),
					manse,
					username,
					subcategoryId,
					request.getPaymentId(),
					request.getSourceTitle()
				);
			}
		});

		log.info("만세력 해석 요청 접수 완료 (비동기 처리 시작): paymentId={}", request.getPaymentId());
		return ResponseEntity.accepted()
			.body(Map.of(
				"message", "해석 요청이 접수되었습니다. 잠시 후 결과를 확인해주세요.",
				"paymentId", request.getPaymentId()
			));
	}

	@PostMapping("/api/v1/manseryeok/interpret/compatibility/{subcategoryId}")
	public ResponseEntity<?> analyzeCompatibility(
		@PathVariable Long subcategoryId,
		@Valid @RequestBody ManseCompatibilityAnalysisRequest request,
		@AuthenticationPrincipal String username
	) {
		ManseCompatibilityAnalysisRequest.PersonInfo person1 = request.getPerson1();
		ManseCompatibilityAnalysisRequest.PersonInfo person2 = request.getPerson2();

		// 1. 두 사람 만세력 계산. 둘 다 끝난 뒤에 해석 시작을 표시해, 두 번째 사람의 입력이 잘못돼도 결과 상태는 그대로다.
		ManseryeokCalculationResponse person1Response = manseCalculationService.calculate(
				new ManseryeokCalculationRequest(
					person1.getName(),
					person1.getSolarDate(),
					person1.getSolarTime(),
					person1.getGender(),
					person1.getIsLunar(),
					person1.getLeapMonth()
				)
			);

		ManseryeokCalculationResponse person2Response = manseCalculationService.calculate(
				new ManseryeokCalculationRequest(
					person2.getName(),
					person2.getSolarDate(),
					person2.getSolarTime(),
					person2.getGender(),
					person2.getIsLunar(),
					person2.getLeapMonth()
				)
			);

		// 2. 해석 시작 표시. 이미 해석 중이거나 완료된 결제면 409 로 끝나고 해석을 제출하지 않는다.
		resultService.startCompatibilityProcessing(request.getPaymentId());

		// 3. 비동기 해석 제출
		submitOrRollback(request.getPaymentId(),
			resultService::rollbackCompatibilityStatusByPaymentId,
			() -> manseInterpretationService.analyzeCompatibilityWithSubcategory(
				person1.getName(), person1Response,
				person2.getName(), person2Response,
				subcategoryId,
				request.getPaymentId(),
				username,
				person1.getSourceTitle(),
				person2.getSourceTitle() // 상대방 캐릭터
			));

		log.info("궁합 분석 요청 접수 완료 (비동기 처리 시작): paymentId={}", request.getPaymentId());
		return ResponseEntity.accepted()
			.body(Map.of(
				"message", "궁합 분석 요청이 접수되었습니다. 잠시 후 결과를 확인해주세요.",
				"paymentId", request.getPaymentId()
			));
	}

	@PostMapping("/api/v1/manseryeok/interpret/free/{subcategoryId}")
	public ResponseEntity<?> interpretFree(
		@PathVariable Long subcategoryId,
		@Valid @RequestBody ManseInterpretationRequest request,
		@AuthenticationPrincipal String username
	) {
		log.info("🆓 무료 해석 요청 진입: user={}, category={}", username, subcategoryId);

		// 1. [동기] 0원 주문/결제 생성 (PaymentService.createFreeOrder 사용)
		Payment payment = paymentService.createFreeOrder(username, subcategoryId);

		// 2. 만세력 계산
		ManseryeokCalculationResponse manse = manseCalculationService.calculate(
				new ManseryeokCalculationRequest(
					request.getName(),
					request.getSolarDate(),
					request.getSolarTime(),
					request.getGender(),
					request.getIsLunar(),
					request.getLeapMonth()
				)
			);

		// 3. 상태 변경 INPUT_REQUIRED -> PROCESSING
		resultService.startProcessing(payment.getId());

		// 4. [비동기] 무료 전용 해석 메서드 호출 (별도 스레드 풀)
		submitOrRollback(payment.getId(), resultService::rollbackStatusByPaymentId,
			() -> manseInterpretationService.interpretFree(
				request.getName(),
				manse,
				username,
				subcategoryId,
				payment.getId()
			));

		return ResponseEntity.accepted().body(Map.of(
			"message", "분석이 시작되었습니다. 잠시 후 결과를 확인해 주세요.",
			"paymentId", payment.getId()
		));
	}

	@PostMapping("/api/v1/manseryeok/compatibility/free/{subcategoryId}")
	public ResponseEntity<?> analyzeCompatibilityFree(
		@PathVariable Long subcategoryId,
		@Valid @RequestBody CompatibilityAnalysisRequest request, // 👈 2인용 DTO
		@AuthenticationPrincipal String username
	) {
		log.info("🆓 무료 궁합/재회운 요청 진입: user={}, category={}", username, subcategoryId);

		// 1. [동기] 0원 주문/결제 생성
		Payment payment = paymentService.createFreeOrder(username, subcategoryId);

		// 2. 두 사람 만세력 계산
		ManseryeokCalculationResponse p1Manse = manseCalculationService.calculate(
			ManseryeokCalculationRequest.from(request.getPerson1())
		);
		ManseryeokCalculationResponse p2Manse = manseCalculationService.calculate(
			ManseryeokCalculationRequest.from(request.getPerson2())
		);

		// 3. 상태 변경
		resultService.startCompatibilityProcessing(payment.getId());

		// 4. [비동기] 무료 궁합 해석 서비스 호출
		submitOrRollback(payment.getId(), resultService::rollbackCompatibilityStatusByPaymentId,
			() -> manseInterpretationService.analyzeCompatibilityFree(
				request.getPerson1().getName(), p1Manse,
				request.getPerson2().getName(), p2Manse,
				subcategoryId,
				payment.getId(),
				username
			));

		return ResponseEntity.accepted().body(Map.of(
			"message", "무료 궁합/재회운 분석이 시작되었습니다.",
			"paymentId", payment.getId()
		));
	}

	/**
	 * 비동기 해석 제출을 감싼다.
	 *
	 * <p>제출 직전에 결과 상태를 PROCESSING 으로 바꿔 두는데, 스레드 풀이 포화면
	 * {@code TaskRejectedException}(= {@link RejectedExecutionException} 의 하위 타입)이
	 * 제출 스레드, 즉 이 요청 스레드에서 그대로 튀어나온다. 해석은 시작조차 하지 않았으므로
	 * 되돌리지 않으면 결과가 PROCESSING 에 남는다. 상태를 되돌린 뒤 예외는 그대로 올려
	 * GlobalExceptionHandler 가 503("잠시 후 다시")로 내려 주게 한다.
	 *
	 * <p>여기까지 오는 요청은 해석 시작 표시(startProcessing)를 통과한 하나뿐이다. 그래서 되돌리는 것은
	 * 이 요청이 바꾼 상태이고, 같은 결제로 먼저 접수된 다른 요청의 해석 중 상태를 지우지 않는다.
	 */
	private void submitOrRollback(Long paymentId, Consumer<Long> rollback, Runnable submission) {
		try {
			submission.run();
		} catch (RejectedExecutionException e) {
			log.error("비동기 해석 제출이 거부되었습니다. 상태를 되돌립니다: paymentId={}", paymentId, e);
			try {
				rollback.accept(paymentId);
			} catch (Exception rollbackFailure) {
				// 되돌리기까지 실패해도 원래의 거부 예외를 덮지 않는다 (Effective Java 아이템 77).
				log.error("제출 거부 후 상태 되돌리기 실패: paymentId={}", paymentId, rollbackFailure);
			}
			throw e;
		}
	}
}
