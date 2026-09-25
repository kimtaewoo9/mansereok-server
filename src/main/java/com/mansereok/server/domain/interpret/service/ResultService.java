package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.entity.ResultStatus;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.global.exception.PaymentException;
import jakarta.persistence.EntityNotFoundException;
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

	@Transactional
	public void updateStatusToProcessing(Long paymentId) {
		Result result = resultRepository.findByPaymentId(paymentId)
			.orElseThrow(() -> new EntityNotFoundException("Result not found"));

		if (result.getStatus() == ResultStatus.INPUT_REQUIRED) {
			result.setStatus(ResultStatus.PROCESSING);
			resultRepository.save(result);
		}
	}

	@Transactional
	public void updateCompatibilityStatusToProcessing(Long paymentId) {
		CompatibilityResult result = compatibilityResultRepository.findByPaymentId(paymentId)
			.orElseThrow(() -> new EntityNotFoundException("CompatibilityResult not found"));

		if (result.getStatus() == ResultStatus.INPUT_REQUIRED) {
			result.setStatus(ResultStatus.PROCESSING);
			compatibilityResultRepository.save(result);
		}
	}

	/**
	 * PROCESSING 으로 바꿔 둔 상태를 INPUT_REQUIRED 로 되돌린다.
	 *
	 * <p>컨트롤러는 비동기 제출 직전에 상태를 PROCESSING 으로 바꾸는데, 스레드 풀이 포화면
	 * 제출 자체가 거부되어 해석이 시작조차 하지 않는다. 그 행을 되돌리지 않으면 결과가
	 * 영원히 PROCESSING 에 남아 사용자가 재시도도 못 한다. 결과 ID 가 아니라 결제 ID 로 찾는
	 * 이유는, 거부 시점에는 아직 비동기 쪽 resultId 를 모르기 때문이다.
	 */
	@Transactional
	public void rollbackStatusByPaymentId(Long paymentId) {
		resultRepository.findByPaymentId(paymentId).ifPresent(result -> {
			if (result.getStatus() == ResultStatus.PROCESSING) {
				result.setStatus(ResultStatus.INPUT_REQUIRED);
				resultRepository.save(result);
			}
		});
	}

	/** 궁합 결과의 PROCESSING 상태를 결제 ID 로 찾아 INPUT_REQUIRED 로 되돌린다. */
	@Transactional
	public void rollbackCompatibilityStatusByPaymentId(Long paymentId) {
		compatibilityResultRepository.findByPaymentId(paymentId).ifPresent(result -> {
			if (result.getStatus() == ResultStatus.PROCESSING) {
				result.setStatus(ResultStatus.INPUT_REQUIRED);
				compatibilityResultRepository.save(result);
			}
		});
	}
}
