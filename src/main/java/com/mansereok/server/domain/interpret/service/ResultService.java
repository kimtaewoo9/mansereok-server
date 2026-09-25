package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.exception.InterpretationAlreadyStartedException;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.global.exception.PaymentException;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class ResultService {

	private final ResultRepository resultRepository;
	private final CompatibilityResultRepository compatibilityResultRepository;
	private final SubCategoryRepository subCategoryRepository;
	private final Clock clock;

	public void createInitialResult(Payment savedPayment, Order savedOrder) {
		Long paymentPkId = savedPayment.getId();
		Long userId = savedOrder.getUserId();
		Long subCategoryId = savedOrder.getSubCategoryId();

		SubCategory subCategory = subCategoryRepository.findById(subCategoryId)
			.orElseThrow(() -> {
				log.error("Result 생성 중 SubCategory 조회 실패: subCategoryId={}", subCategoryId);
				return new PaymentException("상품 정보를 찾을 수 없습니다: ID " + subCategoryId);
			});
		String productName = subCategory.getTitle();

		log.info("[ResultCreationService] subcategoryId = {}", subCategoryId);

		// Category ID에 따라 Result 또는 CompatibilityResult 생성 분기
		if (subCategoryId == 4 ||
			subCategoryId == 6 ||
			subCategoryId == 7 ||
			subCategoryId == 10 ||
			subCategoryId == 11 ||
			subCategoryId == 14 ||
			subCategoryId == 15 ||
			subCategoryId == 19
		) {
			if (compatibilityResultRepository.findByPaymentId(paymentPkId).isEmpty()) {
				CompatibilityResult initialCompResult = CompatibilityResult.createInitial(userId,
					paymentPkId, productName);
				compatibilityResultRepository.save(initialCompResult);
				log.info(
					"초기 CompatibilityResult 생성 완료: paymentId(PK)={}, resultId={}, productName={}",
					paymentPkId, initialCompResult.getId(), productName);
			} else {
				log.warn("이미 paymentId(PK) {}에 해당하는 CompatibilityResult가 존재하여 생성을 건너 뜁니다.",
					paymentPkId);
			}
		} else {
			if (resultRepository.findByPaymentId(paymentPkId).isEmpty()) {
				Result initialResult = Result.createInitial(userId, paymentPkId, productName);
				resultRepository.save(initialResult);
				log.info("초기 Result 생성 완료: paymentId(PK)={}, resultId={}, productName={}",
					paymentPkId, initialResult.getId(), productName);
			} else {
				log.warn("이미 paymentId(PK) {}에 해당하는 Result가 존재하여 생성을 건너 뜁니다.", paymentPkId);
			}
		}
	}

	/**
	 * 결제 한 건의 사주 해석을 시작한다. 정보 입력 대기(INPUT_REQUIRED)인 결과만 해석 중(PROCESSING)으로 바꾼다.
	 *
	 * <p>상태 확인과 변경을 조건부 UPDATE 한 문장으로 하므로, 같은 결제로 요청이 동시에 와도 한 요청만 통과한다. 통과하지 못한
	 * 요청은 예외로 끝나 비동기 해석을 제출하지 않는다. 결제 확인과 한 트랜잭션으로 묶으려면 그 트랜잭션 안에서 부른다(클래스의
	 * {@code @Transactional} 이 합류한다).
	 *
	 * @throws EntityNotFoundException               결제 ID 에 해당하는 결과가 없을 때
	 * @throws InterpretationAlreadyStartedException 이미 해석 중이거나 완료된 결과일 때(연타, 재전송, 완료된 결제의 재사용)
	 */
	@Transactional
	public void startProcessing(Long paymentId) {
		if (resultRepository.markProcessingIfInputRequired(paymentId, LocalDateTime.now(clock)) > 0) {
			return;
		}
		if (!resultRepository.existsByPaymentId(paymentId)) {
			throw new EntityNotFoundException("Result not found");
		}
		throw new InterpretationAlreadyStartedException(paymentId);
	}

	/**
	 * 결제 한 건의 궁합 해석을 시작한다. {@link #startProcessing(Long)} 과 같은 규칙으로 궁합 결과를 바꾼다.
	 *
	 * @throws EntityNotFoundException               결제 ID 에 해당하는 궁합 결과가 없을 때
	 * @throws InterpretationAlreadyStartedException 이미 해석 중이거나 완료된 궁합 결과일 때
	 */
	@Transactional
	public void startCompatibilityProcessing(Long paymentId) {
		if (compatibilityResultRepository.markProcessingIfInputRequired(paymentId, LocalDateTime.now(clock)) > 0) {
			return;
		}
		if (!compatibilityResultRepository.existsByPaymentId(paymentId)) {
			throw new EntityNotFoundException("CompatibilityResult not found");
		}
		throw new InterpretationAlreadyStartedException(paymentId);
	}

	/**
	 * PROCESSING 으로 바꿔 둔 상태를 INPUT_REQUIRED 로 되돌린다.
	 *
	 * <p>컨트롤러는 비동기 제출 직전에 상태를 PROCESSING 으로 바꾸는데, 스레드 풀이 포화면
	 * 제출 자체가 거부되어 해석이 시작조차 하지 않는다. 그 행을 되돌리지 않으면 결과가
	 * PROCESSING 에 남아, 오래 멈춘 결과를 되돌리는 작업(StaleProcessingResultScheduler)이 돌 때까지
	 * 사용자가 재시도도 못 한다. 결과 ID 가 아니라 결제 ID 로 찾는
	 * 이유는, 거부 시점에는 아직 비동기 쪽 resultId 를 모르기 때문이다. 제출까지 오는 요청은
	 * {@link #startProcessing(Long)} 을 통과한 하나뿐이라, 되돌리는 것은 그 요청이 바꾼 상태다.
	 */
	@Transactional
	public void rollbackStatusByPaymentId(Long paymentId) {
		resultRepository.findByPaymentId(paymentId).ifPresent(Result::revertToInputRequired);
	}

	/** 궁합 결과의 PROCESSING 상태를 결제 ID 로 찾아 INPUT_REQUIRED 로 되돌린다. */
	@Transactional
	public void rollbackCompatibilityStatusByPaymentId(Long paymentId) {
		compatibilityResultRepository.findByPaymentId(paymentId)
			.ifPresent(CompatibilityResult::revertToInputRequired);
	}
}
