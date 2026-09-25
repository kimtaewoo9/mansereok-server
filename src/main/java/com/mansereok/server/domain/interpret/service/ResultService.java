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
import org.springframework.transaction.annotation.Propagation;
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
	 * 결제에 딸린 결과 행을 잠그고 그 상태를 돌려준다. 일반 사주(Result)와 궁합(CompatibilityResult) 중 행이 있는 쪽만 잠가
	 * 읽는다. 둘 다 없으면 빈 Optional 이다.
	 *
	 * <p>환불(PaymentRefundService 의 트랜잭션 A)이 결제 행 → 주문 행을 잠근 뒤 부른다. 상태를 잠그지 않고 읽으면 REPEATABLE READ 의
	 * 스냅샷이 결제 행 잠금을 기다리기 전에 잡혀, 그사이 해석 시작이 커밋한 PROCESSING 을 보지 못한다. 잠금은 호출자의 트랜잭션이
	 * 끝날 때 풀리므로 {@link Propagation#MANDATORY} 로 진행 중인 트랜잭션이 없으면 IllegalTransactionStateException 을 던진다.
	 *
	 * <p>어느 표에 행이 있는지는 잠그지 않는 읽기로 먼저 가린다. payment_id 가 UNIQUE 라 행이 없는 표를 잠가 읽으면 그 자리의 간격이
	 * 잠겨, 다른 결제의 초기 결과 INSERT 가 이 트랜잭션이 끝날 때까지 기다린다. 결과 행은 결제 확정 트랜잭션에서 결제와 함께 커밋되고
	 * 환불 전에는 사라지지 않아 스냅샷으로 가려도 된다. 스냅샷이 결제 확정의 커밋보다 앞서 잡힌 드문 경우에는 행을 못 보고 빈
	 * Optional 을 돌려주는데, 환불은 이를 거부하므로 돈은 움직이지 않는다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<ResultStatus> findStatusByPaymentId(Long paymentPkId) {
		if (resultRepository.existsByPaymentId(paymentPkId)) {
			return resultRepository.findByPaymentIdForUpdate(paymentPkId).map(Result::getStatus);
		}
		if (compatibilityResultRepository.existsByPaymentId(paymentPkId)) {
			return compatibilityResultRepository.findByPaymentIdForUpdate(paymentPkId)
				.map(CompatibilityResult::getStatus);
		}
		return Optional.empty();
	}

	/**
	 * 환불 확정 시 정보 입력 전(INPUT_REQUIRED)의 초기 결과를 지운다. 일반 사주(Result)와 궁합(CompatibilityResult)
	 * 중 행이 있는 쪽을 삭제한다.
	 *
	 * <p>"INPUT_REQUIRED 일 때만 지운다" 를 조건부 DELETE 한 번으로 하고 지운 행 수로 판단한다. 엔티티를 읽어 상태를 보면
	 * open-in-view 로 환불 앞 단계에서 읽어 둔 낡은 엔티티가 돌아와, DB 에서는 해석 중인 결과를 INPUT_REQUIRED 로 보고 지운다.
	 *
	 * <p>조건부 DELETE 는 행이 있는 표에만 보낸다. 행이 없는 표에 보내면 0 행을 지우면서도 그 자리의 간격을 잠가
	 * ({@link #findStatusByPaymentId} 와 같은 이유) 다른 결제의 초기 결과 INSERT 를 막는다.
	 *
	 * @throws IllegalStateException 결과가 INPUT_REQUIRED 가 아니거나(해석이 이미 진행됨) 둘 다 없을 때
	 */
	public void deleteInitialResult(Long paymentPkId) {
		if (resultRepository.existsByPaymentId(paymentPkId)) {
			if (resultRepository.deleteByPaymentIdAndStatus(paymentPkId, ResultStatus.INPUT_REQUIRED) == 0) {
				throw notInputRequired("Result", paymentPkId);
			}
			log.info("초기 Result 삭제: paymentId(PK)={}", paymentPkId);
			return;
		}
		if (compatibilityResultRepository.existsByPaymentId(paymentPkId)) {
			if (compatibilityResultRepository.deleteByPaymentIdAndStatus(paymentPkId,
				ResultStatus.INPUT_REQUIRED) == 0) {
				throw notInputRequired("CompatibilityResult", paymentPkId);
			}
			log.info("초기 CompatibilityResult 삭제: paymentId(PK)={}", paymentPkId);
			return;
		}
		throw new IllegalStateException(
			"삭제할 초기 결과가 없습니다. paymentId(PK)=" + paymentPkId);
	}

	private static IllegalStateException notInputRequired(String kind, Long paymentPkId) {
		return new IllegalStateException(String.format(
			"정보 입력 전(INPUT_REQUIRED)의 %s 만 삭제할 수 있습니다. 해석이 이미 진행됐습니다. paymentId(PK)=%s",
			kind, paymentPkId));
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
