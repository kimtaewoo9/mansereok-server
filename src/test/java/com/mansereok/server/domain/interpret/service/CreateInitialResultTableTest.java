package com.mansereok.server.domain.interpret.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.support.fixture.SubCategoryFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 결제 확정 때 첫 결과 행을 사주 결과 표(results)와 궁합 결과 표(compatibility_results) 중 어디에 만드는지 확인한다.
 *
 * <p>예전 ResultService 는 궁합 표로 보낼 상품을 4, 6, 7, 10, 11, 14, 15, 19 라는 숫자로 나열했고, 나머지는 모두 사주 표로
 * 보냈다. 이제 상품 목록(InterpretationProduct)의 결과 표를 보는데, 1~23·101~106 모든 번호가 예전과 같은 표로 가는지 값
 * 그대로 적어 확인한다.
 *
 * <p>리포지토리는 돌려줄 값만 정한다. 결과 행 저장(save)은 상태를 바꾸는 명령이라 어느 리포지토리에 저장했는지를 확인한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("결제 확정 때 첫 결과 행을 만드는 표")
class CreateInitialResultTableTest {

	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-26T00:00:00Z"),
		ZoneId.of("Asia/Seoul"));
	private static final Long PAYMENT_ID = 500L;
	private static final Long USER_ID = 7L;
	private static final String PRODUCT_TITLE = "상품 제목";

	@Mock
	private ResultRepository resultRepository;

	@Mock
	private CompatibilityResultRepository compatibilityResultRepository;

	@Mock
	private SubCategoryRepository subCategoryRepository;

	private ResultService resultService;

	@BeforeEach
	void setUp() {
		resultService = new ResultService(resultRepository, compatibilityResultRepository, subCategoryRepository,
			FIXED_CLOCK);
	}

	@Nested
	@DisplayName("같은 결제의 결과 행이 아직 없으면")
	class WhenNoResultYet {

		@ParameterizedTest(name = "[{index}] 상품 {0}")
		@ValueSource(longs = {4, 6, 7, 10, 11, 14, 15, 19})
		@DisplayName("예전 궁합 목록에 있던 상품은 궁합 결과 표에 만든다")
		void createsCompatibilityResultForOldCompatibilityList(long productId) {
			// given
			givenProduct(productId);
			given(compatibilityResultRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.empty());

			// when
			resultService.createInitialResult(paymentOf(productId), orderOf(productId));

			// then
			ArgumentCaptor<CompatibilityResult> saved = ArgumentCaptor.forClass(CompatibilityResult.class);
			then(compatibilityResultRepository).should().save(saved.capture());
			assertThat(saved.getValue())
				.extracting(CompatibilityResult::getPaymentId, CompatibilityResult::getUserId,
					CompatibilityResult::getProductName)
				.containsExactly(PAYMENT_ID, USER_ID, PRODUCT_TITLE);
			then(resultRepository).should(never()).save(any());
		}

		@ParameterizedTest(name = "[{index}] 상품 {0}")
		@CsvSource(textBlock = """
			# 한 사람의 사주 상품
			1
			2
			3
			5
			9
			13
			17
			18
			20
			21
			22
			23
			# 삼각관계. 궁합 프롬프트로 풀지만 운영 판매 이력을 확인하기 전까지 예전처럼 사주 표에 만든다
			8
			# 상품 목록에 없는 번호
			12
			16
			# 무료 운세 상품
			101
			102
			103
			104
			105
			106
			""")
		@DisplayName("그 밖의 1~23·101~106 번호는 사주 결과 표에 만든다")
		void createsResultForOtherIds(long productId) {
			// given
			givenProduct(productId);
			given(resultRepository.findByPaymentId(PAYMENT_ID)).willReturn(Optional.empty());

			// when
			resultService.createInitialResult(paymentOf(productId), orderOf(productId));

			// then
			ArgumentCaptor<Result> saved = ArgumentCaptor.forClass(Result.class);
			then(resultRepository).should().save(saved.capture());
			assertThat(saved.getValue())
				.extracting(Result::getPaymentId, Result::getUserId, Result::getProductName)
				.containsExactly(PAYMENT_ID, USER_ID, PRODUCT_TITLE);
			then(compatibilityResultRepository).should(never()).save(any());
		}
	}

	@Nested
	@DisplayName("같은 결제의 결과 행이 이미 있으면")
	class WhenResultAlreadyExists {

		@Test
		@DisplayName("궁합 상품이면 궁합 결과를 다시 만들지 않는다")
		void doesNotSaveCompatibilityResultAgain() {
			// given
			givenProduct(19L);
			given(compatibilityResultRepository.findByPaymentId(PAYMENT_ID)).willReturn(
				Optional.of(CompatibilityResult.createInitial(USER_ID, PAYMENT_ID, PRODUCT_TITLE)));

			// when
			resultService.createInitialResult(paymentOf(19L), orderOf(19L));

			// then
			then(compatibilityResultRepository).should(never()).save(any());
			then(resultRepository).should(never()).save(any());
		}

		@Test
		@DisplayName("사주 상품이면 사주 결과를 다시 만들지 않는다")
		void doesNotSaveResultAgain() {
			// given
			givenProduct(1L);
			given(resultRepository.findByPaymentId(PAYMENT_ID)).willReturn(
				Optional.of(Result.createInitial(USER_ID, PAYMENT_ID, PRODUCT_TITLE)));

			// when
			resultService.createInitialResult(paymentOf(1L), orderOf(1L));

			// then
			then(resultRepository).should(never()).save(any());
			then(compatibilityResultRepository).should(never()).save(any());
		}
	}

	private void givenProduct(long productId) {
		given(subCategoryRepository.findById(productId)).willReturn(
			Optional.of(SubCategoryFixture.paidProduct().id(productId).title(PRODUCT_TITLE).build()));
	}

	private static Order orderOf(long productId) {
		return Order.create("order_" + productId, USER_ID, productId, 10000, 10000, null, null,
			OrderStatus.PAID, "구매자", "buyer@example.com");
	}

	private static Payment paymentOf(long productId) {
		Payment payment = Payment.create("imp_" + productId, "order_" + productId, 10000L, PaymentStatus.PAID,
			1L, USER_ID, productId);
		ReflectionTestUtils.setField(payment, "id", PAYMENT_ID);
		return payment;
	}
}
