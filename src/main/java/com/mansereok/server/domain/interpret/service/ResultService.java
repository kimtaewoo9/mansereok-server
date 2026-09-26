package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.exception.InterpretationAlreadyStartedException;
import com.mansereok.server.domain.interpret.product.InterpretationProduct;
import com.mansereok.server.domain.interpret.product.InterpretationProduct.ResultTable;
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
import java.time.temporal.ChronoUnit;
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

		// 상품 목록(InterpretationProduct)이 정한 표에 Result 또는 CompatibilityResult 를 만든다
		if (resultTableOf(subCategoryId) == ResultTable.COMPATIBILITY_RESULTS) {
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
	 * 결제 한 건의 사주 해석을 시작한다. 정보 입력 대기(INPUT_REQUIRED)인 결과만 해석 중(PROCESSING)으로 바꾸고, 해석을 시작한
	 * 시각을 돌려준다.
	 *
	 * <p>상태 확인과 변경을 조건부 UPDATE 한 문장으로 하므로, 같은 결제로 요청이 동시에 와도 한 요청만 통과한다. 통과하지 못한
	 * 요청은 예외로 끝나 비동기 해석을 제출하지 않는다. 결제 확인과 한 트랜잭션으로 묶으려면 그 트랜잭션 안에서 부른다(클래스의
	 * {@code @Transactional} 이 합류한다).
	 *
	 * <p>돌려준 시각은 결과의 updated_at 에 들어가고, 해석 실행은 결과를 쓸 때마다 이 값으로 자기가 시작한 해석인지 가린다
	 * (Result.isProcessingStartedAt). 그래서 비동기 해석에 꼭 넘긴다.
	 *
	 * @return 해석을 시작한 시각(초 단위)
	 * @throws EntityNotFoundException               결제 ID 에 해당하는 결과가 없을 때
	 * @throws InterpretationAlreadyStartedException 이미 해석 중이거나 완료된 결과일 때(연타, 재전송, 완료된 결제의 재사용)
	 */
	@Transactional
	public LocalDateTime startProcessing(Long paymentId) {
		LocalDateTime startedAt = processingStartTime();
		if (resultRepository.markProcessingIfInputRequired(paymentId, startedAt) > 0) {
			return startedAt;
		}
		if (!resultRepository.existsByPaymentId(paymentId)) {
			throw new EntityNotFoundException("Result not found");
		}
		throw new InterpretationAlreadyStartedException(paymentId);
	}

	/**
	 * 결제 한 건의 궁합 해석을 시작한다. {@link #startProcessing(Long)} 과 같은 규칙으로 궁합 결과를 바꾸고 해석을 시작한 시각을
	 * 돌려준다.
	 *
	 * @return 해석을 시작한 시각(초 단위)
	 * @throws EntityNotFoundException               결제 ID 에 해당하는 궁합 결과가 없을 때
	 * @throws InterpretationAlreadyStartedException 이미 해석 중이거나 완료된 궁합 결과일 때
	 */
	@Transactional
	public LocalDateTime startCompatibilityProcessing(Long paymentId) {
		LocalDateTime startedAt = processingStartTime();
		if (compatibilityResultRepository.markProcessingIfInputRequired(paymentId, startedAt) > 0) {
			return startedAt;
		}
		if (!compatibilityResultRepository.existsByPaymentId(paymentId)) {
			throw new EntityNotFoundException("CompatibilityResult not found");
		}
		throw new InterpretationAlreadyStartedException(paymentId);
	}

	/**
	 * 해석을 시작한 시각. DB 에 넣은 값과 다시 읽은 값이 같아야 해석 실행이 자기가 시작한 해석을 알아본다. 운영 updated_at 칸의 소수
	 * 초 자릿수를 아직 확인하지 않아(schema.sql 주석) 초 단위로 잘라, 어느 자릿수의 칸이어도 반올림 없이 그대로 담기게 한다.
	 */
	private LocalDateTime processingStartTime() {
		return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
	}

	/**
	 * 비동기 해석 제출이 거부됐거나 해석이 실패했을 때, startedAt 에 시작한 사주 해석을 정보 입력 대기(INPUT_REQUIRED)로 되돌린다.
	 *
	 * <p>컨트롤러는 비동기 제출 직전에 해석을 시작하는데, 스레드 풀이 포화면 제출 자체가 거부되어 해석이 시작조차 하지 않는다. 해석
	 * 실행(InterpretationPipeline)도 어느 단계에서든 실패하면 여기로 되돌린다. 그 행을 되돌리지 않으면 결과가 PROCESSING 에 남아,
	 * 오래 멈춘 결과를 되돌리는 작업(StaleProcessingResultScheduler)이 돌 때까지 사용자가 재시도도 못 한다. 결과 ID 가 아니라 결제
	 * ID 로 찾는 이유는, 제출이 거부됐거나 해석 실행의 첫 DB 단계가 실패했을 때는 결과 ID 를 모르기 때문이다.
	 *
	 * <p>행을 잠그고 이 요청이 시작한 해석인지(Result.isProcessingStartedAt) 확인한 뒤에만 되돌린다. 그래서 다른 요청이 시작한 해석
	 * 중 상태나 완료된 결과는 건드리지 않는다.
	 */
	@Transactional
	public void rollbackStatusByPaymentId(Long paymentId, LocalDateTime startedAt) {
		resultRepository.findByPaymentIdForUpdate(paymentId)
			.filter(result -> result.isProcessingStartedAt(startedAt))
			.ifPresent(Result::revertToInputRequired);
	}

	/**
	 * 비동기 해석 제출이 거부됐거나 해석이 실패했을 때, startedAt 에 시작한 궁합 해석을 결제 ID 로 찾아 INPUT_REQUIRED 로 되돌린다.
	 * {@link #rollbackStatusByPaymentId} 와 같은 규칙이다.
	 */
	@Transactional
	public void rollbackCompatibilityStatusByPaymentId(Long paymentId, LocalDateTime startedAt) {
		compatibilityResultRepository.findByPaymentIdForUpdate(paymentId)
			.filter(result -> result.isProcessingStartedAt(startedAt))
			.ifPresent(CompatibilityResult::revertToInputRequired);
	}

	/**
	 * 결제 확정 때 첫 결과 행을 만들 표. 상품 목록에 없는 번호는 예전처럼 사주 결과 표(results)에 만들고, 목록을 고쳐야
	 * 한다는 것을 알 수 있게 경고를 남긴다.
	 */
	private static ResultTable resultTableOf(Long subCategoryId) {
		return InterpretationProduct.find(subCategoryId)
			.map(InterpretationProduct::resultTable)
			.orElseGet(() -> {
				log.warn("해석 상품 목록에 없는 상품이라 사주 결과 표(results)에 만듭니다: subCategoryId={}", subCategoryId);
				return ResultTable.RESULTS;
			});
	}
}
