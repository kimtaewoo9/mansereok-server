package com.mansereok.server.domain.interpret.controller;

import com.mansereok.server.domain.interpret.dto.request.ManseCompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseInterpretationRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCalculationRequest;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.interpret.service.ManseCalculationService;
import com.mansereok.server.domain.interpret.service.ManseInterpretationService;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.service.PaymentService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
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

	private final ResultRepository resultRepository; // 직접 주입
	private final CompatibilityResultRepository compatibilityResultRepository; // 직접 주입

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
		resultService.updateStatusToProcessing(request.getPaymentId());

		ManseryeokCalculationResponse manse = manseCalculationService.calculate(
			new ManseryeokCalculationRequest(
				request.getName(),
				request.getSolarDate(),
				request.getSolarTime(),
				request.getGender(),
				request.getIsLunar()
			)
		);

		manseInterpretationService.interpret(
			request.getName(),
			manse,
			username,
			subcategoryId,
			request.getPaymentId(),
			request.getSourceTitle()
		);

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

		resultService.updateCompatibilityStatusToProcessing(request.getPaymentId());

		ManseryeokCalculationResponse person1Response = manseCalculationService.calculate(
			new ManseryeokCalculationRequest(
				person1.getName(),
				person1.getSolarDate(),
				person1.getSolarTime(),
				person1.getGender(),
				person1.getIsLunar()
			)
		);

		ManseryeokCalculationResponse person2Response = manseCalculationService.calculate(
			new ManseryeokCalculationRequest(
				person2.getName(),
				person2.getSolarDate(),
				person2.getSolarTime(),
				person2.getGender(),
				person2.getIsLunar()
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

		// 1. [동기] 0원 주문/결제 생성 (PaymentService.createFreeOrder 사용)
		Payment payment = paymentService.createFreeOrder(username, subcategoryId);

		// 2. 만세력 계산
		ManseryeokCalculationResponse manse = manseCalculationService.calculate(
			new ManseryeokCalculationRequest(
				request.getName(),
				request.getSolarDate(),
				request.getSolarTime(),
				request.getGender(),
				request.getIsLunar()
			)
		);

		// 3. 상태 변경 INPUT_REQUIRED -> PROCESSING
		resultService.updateStatusToProcessing(payment.getId());

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

	@Transactional
	protected void updateResultStatusToProcessing(Long paymentId) {
		Result result = resultRepository.findByPaymentId(paymentId)
			.orElseThrow(() -> {
				log.error("Payment ID {}에 해당하는 Result를 찾을 수 없습니다.", paymentId);
				return new EntityNotFoundException("결과 정보를 찾을 수 없습니다.");
			});
		if (result.getStatus() == ResultStatus.INPUT_REQUIRED) { // INPUT_REQUIRED 상태일 때만 변경
			result.setStatus(ResultStatus.PROCESSING);
			resultRepository.save(result);
			log.info("Result 상태 PROCESSING으로 변경 완료: paymentId={}", paymentId);
		} else {
			log.warn(
				"Result 상태가 INPUT_REQUIRED가 아니므로 PROCESSING으로 변경하지 않음: paymentId={}, currentStatus={}",
				paymentId, result.getStatus());
		}
	}

	@Transactional
	protected void updateCompatibilityResultStatusToProcessing(Long paymentId) {
		CompatibilityResult result = compatibilityResultRepository.findByPaymentId(paymentId)
			.orElseThrow(() -> {
				log.error("Payment ID {}에 해당하는 CompatibilityResult를 찾을 수 없습니다.", paymentId);
				return new EntityNotFoundException("궁합 결과 정보를 찾을 수 없습니다.");
			});
		if (result.getStatus() == ResultStatus.INPUT_REQUIRED) { // INPUT_REQUIRED 상태일 때만 변경
			result.setStatus(ResultStatus.PROCESSING);
			compatibilityResultRepository.save(result);
			log.info("CompatibilityResult 상태 PROCESSING으로 변경 완료: paymentId={}", paymentId);
		} else {
			log.warn(
				"CompatibilityResult 상태가 INPUT_REQUIRED가 아니므로 PROCESSING으로 변경하지 않음: paymentId={}, currentStatus={}",
				paymentId, result.getStatus());
		}
	}
}
