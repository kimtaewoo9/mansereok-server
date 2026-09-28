package com.mansereok.server.domain.interpret.controller;

import com.mansereok.server.domain.interpret.dto.request.CompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseCompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseInterpretationRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCalculationRequest;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.product.InterpretationProduct;
import com.mansereok.server.domain.interpret.product.InterpretationProduct.Kind;
import com.mansereok.server.domain.interpret.prompt.CompatibilityPromptContext;
import com.mansereok.server.domain.interpret.prompt.PromptContext;
import com.mansereok.server.domain.interpret.service.CompatibilityInterpretationCommand;
import com.mansereok.server.domain.interpret.service.ManseCalculationService;
import com.mansereok.server.domain.interpret.service.ManseInterpretationService;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.interpret.service.SajuInterpretationCommand;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.service.PaymentService;
import jakarta.validation.Valid;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
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
		// 1. 상품 확인. 사주 상품과 무료 운세 상품만 받는다. 궁합 상품이나 목록에 없는 번호는 여기서 400 으로 끝난다.
		InterpretationProduct product = requireProduct(subcategoryId, Kind.SAJU, Kind.FREE_FORTUNE);
		log.info("만세력 해석 요청: paymentId={}, subcategoryId={}", request.getPaymentId(), subcategoryId);

		// 2. 만세력 계산 (공통). DB 를 읽기만 하므로 입력이 잘못돼 실패해도 결과 상태는 그대로다.
		ManseryeokCalculationResponse manse = manseCalculationService.calculate(
			ManseryeokCalculationRequest.from(request));

		// 3. 해석 시작 표시 (공통). 이미 해석 중이거나 완료된 결제면 409 로 끝나고 해석을 제출하지 않는다.
		LocalDateTime startedAt = resultService.startProcessing(request.getPaymentId());

		// 4. 비동기 해석 제출. 해석을 시작한 시각을 넘겨, 늦게 끝난 해석이 그사이 되돌려지거나 다시 시작된 결과를 덮어쓰지 않게 한다.
		SajuInterpretationCommand command = new SajuInterpretationCommand(request.getPaymentId(), startedAt, product,
			username, PromptContext.of(request.getName(), manse, request.getSourceTitle()));
		Runnable rollback = () -> resultService.rollbackStatusByPaymentId(request.getPaymentId(), startedAt);
		submitOrRollback(request.getPaymentId(), rollback, () -> {
			if (product.usesFreePrompt()) {
				manseInterpretationService.interpretFree(command);
			} else {
				manseInterpretationService.interpret(command);
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
		// 1. 상품 확인. 궁합 상품만 받는다.
		InterpretationProduct product = requireProduct(subcategoryId, Kind.COMPATIBILITY);

		ManseCompatibilityAnalysisRequest.PersonInfo person1 = request.getPerson1();
		ManseCompatibilityAnalysisRequest.PersonInfo person2 = request.getPerson2();

		// 2. 두 사람 만세력 계산. 둘 다 끝난 뒤에 해석 시작을 표시해, 두 번째 사람의 입력이 잘못돼도 결과 상태는 그대로다.
		ManseryeokCalculationResponse person1Response = manseCalculationService.calculate(
			ManseryeokCalculationRequest.from(person1));
		ManseryeokCalculationResponse person2Response = manseCalculationService.calculate(
			ManseryeokCalculationRequest.from(person2));

		// 3. 해석 시작 표시. 이미 해석 중이거나 완료된 결제면 409 로 끝나고 해석을 제출하지 않는다.
		LocalDateTime startedAt = resultService.startCompatibilityProcessing(request.getPaymentId());

		// 4. 비동기 해석 제출. 해석을 시작한 시각을 함께 넘긴다. 두 번째 사람의 작품명은 상대방 캐릭터의 작품이다.
		CompatibilityInterpretationCommand command = new CompatibilityInterpretationCommand(request.getPaymentId(),
			startedAt, product, username, CompatibilityPromptContext.of(
				person1.getName(), person1Response, person1.getSourceTitle(),
				person2.getName(), person2Response, person2.getSourceTitle()));
		submitOrRollback(request.getPaymentId(),
			() -> resultService.rollbackCompatibilityStatusByPaymentId(request.getPaymentId(), startedAt),
			() -> manseInterpretationService.analyzeCompatibilityWithSubcategory(command));

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
		// 1. 상품 확인. 무료 운세 상품만 받는다.
		InterpretationProduct product = requireProduct(subcategoryId, Kind.FREE_FORTUNE);

		// 2. 만세력 계산. 0원 주문은 되돌리지 않으므로, 입력 때문에 실패할 수 있는 계산을 주문보다 먼저 끝낸다.
		ManseryeokCalculationResponse manse = manseCalculationService.calculate(
			ManseryeokCalculationRequest.from(request));

		// 3. [동기] 0원 주문/결제 생성 (PaymentService.createFreeOrder 사용)
		Payment payment = paymentService.createFreeOrder(username, subcategoryId);
		log.info("🆓 무료 해석 요청: paymentId={}, subcategoryId={}", payment.getId(), subcategoryId);

		// 4. 상태 변경 INPUT_REQUIRED -> PROCESSING
		LocalDateTime startedAt = resultService.startProcessing(payment.getId());

		// 5. [비동기] 무료 전용 해석 메서드 호출 (별도 스레드 풀). 무료 운세 프롬프트는 작품명을 쓰지 않는다.
		SajuInterpretationCommand command = new SajuInterpretationCommand(payment.getId(), startedAt, product,
			username, PromptContext.of(request.getName(), manse));
		submitOrRollback(payment.getId(), () -> resultService.rollbackStatusByPaymentId(payment.getId(), startedAt),
			() -> manseInterpretationService.interpretFree(command));

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
		// 1. 상품 확인. 궁합 상품만 받는다.
		InterpretationProduct product = requireProduct(subcategoryId, Kind.COMPATIBILITY);

		// 2. 두 사람 입력 변환과 만세력 계산. 0원 주문은 되돌리지 않으므로, 두 사람 모두 계산이 끝난 뒤에 주문을 만든다.
		ManseryeokCalculationResponse p1Manse = manseCalculationService.calculate(
			ManseryeokCalculationRequest.from(request.getPerson1())
		);
		ManseryeokCalculationResponse p2Manse = manseCalculationService.calculate(
			ManseryeokCalculationRequest.from(request.getPerson2())
		);

		// 3. [동기] 0원 주문/결제 생성
		Payment payment = paymentService.createFreeOrder(username, subcategoryId);
		log.info("🆓 무료 궁합/재회운 요청: paymentId={}, subcategoryId={}", payment.getId(), subcategoryId);

		// 4. 상태 변경
		LocalDateTime startedAt = resultService.startCompatibilityProcessing(payment.getId());

		// 5. [비동기] 무료 궁합 해석 서비스 호출
		CompatibilityInterpretationCommand command = new CompatibilityInterpretationCommand(payment.getId(),
			startedAt, product, username, CompatibilityPromptContext.of(
				request.getPerson1().getName(), p1Manse,
				request.getPerson2().getName(), p2Manse));
		submitOrRollback(payment.getId(),
			() -> resultService.rollbackCompatibilityStatusByPaymentId(payment.getId(), startedAt),
			() -> manseInterpretationService.analyzeCompatibilityFree(command));

		return ResponseEntity.accepted().body(Map.of(
			"message", "무료 궁합/재회운 분석이 시작되었습니다.",
			"paymentId", payment.getId()
		));
	}

	/**
	 * 경로의 상품 번호가 이 엔드포인트가 맡는 종류의 상품인지 확인한다. 아니면 IllegalArgumentException 이라 400 으로 끝난다.
	 *
	 * <p>계산·주문·상태 변경보다 먼저 부른다. 이 확인이 없으면 맞지 않는 번호도 202 로 접수된 뒤 비동기 해석 안에서야 실패하고, 무료
	 * 경로는 그 전에 0원 주문까지 남긴다.
	 */
	private static InterpretationProduct requireProduct(Long subcategoryId, Kind... acceptedKinds) {
		InterpretationProduct product = InterpretationProduct.require(subcategoryId);
		if (!List.of(acceptedKinds).contains(product.kind())) {
			throw InterpretationProduct.unsupported(subcategoryId);
		}
		return product;
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
	 * <p>여기까지 오는 요청은 해석 시작 표시(startProcessing)를 통과한 하나뿐이다. 되돌리기도 이 요청이 해석을
	 * 시작한 시각을 걸어, 같은 결제로 먼저 접수된 다른 요청의 해석 중 상태를 지우지 않는다.
	 */
	private void submitOrRollback(Long paymentId, Runnable rollback, Runnable submission) {
		try {
			submission.run();
		} catch (RejectedExecutionException e) {
			log.error("비동기 해석 제출이 거부되었습니다. 상태를 되돌립니다: paymentId={}", paymentId, e);
			try {
				rollback.run();
			} catch (Exception rollbackFailure) {
				// 되돌리기까지 실패해도 원래의 거부 예외를 덮지 않는다 (Effective Java 아이템 77).
				log.error("제출 거부 후 상태 되돌리기 실패: paymentId={}", paymentId, rollbackFailure);
			}
			throw e;
		}
	}
}
