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
import java.util.Optional;
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

	/**
	 * 결제에 딸린 결과의 상태를 돌려준다. 일반 사주(Result)를 먼저 보고, 없으면 궁합(CompatibilityResult)을 본다.
	 * 둘 다 없으면 빈 Optional 이다.
	 */
	@Transactional(readOnly = true)
	public Optional<ResultStatus> findStatusByPaymentId(Long paymentPkId) {
		Optional<ResultStatus> status = resultRepository.findByPaymentId(paymentPkId)
			.map(Result::getStatus);
		if (status.isPresent()) {
			return status;
		}
		return compatibilityResultRepository.findByPaymentId(paymentPkId)
			.map(CompatibilityResult::getStatus);
	}

	/**
	 * 환불 확정 시 정보 입력 전(INPUT_REQUIRED)의 초기 결과를 지운다. 일반 사주(Result)와 궁합(CompatibilityResult)
	 * 중 존재하는 쪽을 삭제한다.
	 *
	 * @throws IllegalStateException 결과가 INPUT_REQUIRED 가 아니거나(해석이 이미 진행됨) 둘 다 없을 때
	 */
	public void deleteInitialResult(Long paymentPkId) {
		Optional<Result> result = resultRepository.findByPaymentId(paymentPkId);
		if (result.isPresent()) {
			assertInputRequired(result.get().getStatus(), paymentPkId, "Result");
			resultRepository.delete(result.get());
			log.info("초기 Result 삭제: paymentId(PK)={}, resultId={}", paymentPkId,
				result.get().getId());
			return;
		}

		Optional<CompatibilityResult> compatibilityResult =
			compatibilityResultRepository.findByPaymentId(paymentPkId);
		if (compatibilityResult.isPresent()) {
			assertInputRequired(compatibilityResult.get().getStatus(), paymentPkId,
				"CompatibilityResult");
			compatibilityResultRepository.delete(compatibilityResult.get());
			log.info("초기 CompatibilityResult 삭제: paymentId(PK)={}, resultId={}", paymentPkId,
				compatibilityResult.get().getId());
			return;
		}

		throw new IllegalStateException(
			"삭제할 초기 결과가 없습니다. paymentId(PK)=" + paymentPkId);
	}

	private static void assertInputRequired(ResultStatus status, Long paymentPkId, String kind) {
		if (status != ResultStatus.INPUT_REQUIRED) {
			throw new IllegalStateException(String.format(
				"정보 입력 전(INPUT_REQUIRED)의 %s 만 삭제할 수 있습니다. paymentId(PK)=%s, status=%s",
				kind, paymentPkId, status));
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
}
