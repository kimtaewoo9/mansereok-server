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
import com.mansereok.server.domain.payment.service.PaymentEntitlementService;
import com.mansereok.server.domain.payment.service.PaymentOrderService;
import jakarta.validation.Valid;
import java.util.Map;
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

	private final PaymentEntitlementService paymentEntitlementService;
	private final PaymentOrderService paymentOrderService;
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

		// 1. 결제 행을 잠근 채 본인의 결제 완료 건이고 결제한 상품이 경로의 상품과 같은지 확인한 뒤 상태 변경
		paymentEntitlementService.startInterpretation(request.getPaymentId(), username, subcategoryId,
			resultService::updateStatusToProcessing);

		// 2. 만세력 계산 (공통)
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

		// 결제 행을 잠근 채 본인의 결제 완료 건이고 결제한 상품이 경로의 상품과 같은지 확인한 뒤 상태 변경
		paymentEntitlementService.startInterpretation(request.getPaymentId(), username, subcategoryId,
			resultService::updateCompatibilityStatusToProcessing);

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

		manseInterpretationService.analyzeCompatibilityWithSubcategory(
			person1.getName(), person1Response,
			person2.getName(), person2Response,
			subcategoryId,
			request.getPaymentId(),
			username,
			person1.getSourceTitle(),
			person2.getSourceTitle() // 상대방 캐릭터
		);

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

		// 1. [동기] 0원 주문/결제 생성 (PaymentOrderService.createFreeOrder 사용)
		Payment payment = paymentOrderService.createFreeOrder(username, subcategoryId);

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

		// 3. 상태 변경 INPUT_REQUIRED -> PROCESSING (유료 해석과 같은 결제 확인을 거친다)
		paymentEntitlementService.startInterpretation(payment.getId(), username, subcategoryId,
			resultService::updateStatusToProcessing);

		// 4. [비동기] 무료 전용 해석 메서드 호출 (별도 스레드 풀)
		manseInterpretationService.interpretFree(
			request.getName(),
			manse,
			username,
			subcategoryId,
			payment.getId()
		);

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
		Payment payment = paymentOrderService.createFreeOrder(username, subcategoryId);

		// 2. 두 사람 만세력 계산
		ManseryeokCalculationResponse p1Manse = manseCalculationService.calculate(
			ManseryeokCalculationRequest.from(request.getPerson1())
		);
		ManseryeokCalculationResponse p2Manse = manseCalculationService.calculate(
			ManseryeokCalculationRequest.from(request.getPerson2())
		);

		// 3. 상태 변경 (유료 해석과 같은 결제 확인을 거친다)
		paymentEntitlementService.startInterpretation(payment.getId(), username, subcategoryId,
			resultService::updateCompatibilityStatusToProcessing);

		// 4. [비동기] 무료 궁합 해석 서비스 호출
		manseInterpretationService.analyzeCompatibilityFree(
			request.getPerson1().getName(), p1Manse,
			request.getPerson2().getName(), p2Manse,
			subcategoryId,
			payment.getId(),
			username
		);

		return ResponseEntity.accepted().body(Map.of(
			"message", "무료 궁합/재회운 분석이 시작되었습니다.",
			"paymentId", payment.getId()
		));
	}
}
