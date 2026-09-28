package com.mansereok.server.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.coupon.entity.CouponTemplate;
import com.mansereok.server.domain.coupon.repository.CouponRepository;
import com.mansereok.server.domain.coupon.repository.CouponTemplateRepository;
import com.mansereok.server.domain.discount.service.DiscountCodeService.DiscountValidationResult;
import com.mansereok.server.global.exception.CouponSoldOutException;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.fixture.CouponFixture;
import com.mansereok.server.support.fixture.CouponTemplateFixture;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 쿠폰 서비스가 지키는 규칙을 확인한다. 결제 때 쿠폰을 쓸 수 있는지(본인 쿠폰, 미사용, 있는 쿠폰), 주문을 만들며 사용 처리할 때의
 * 만료 거절, 쿠폰 id 가 없는 주문의 되돌리기, 쿠폰 받기의 발급 기간과 선착순 상한이다.
 *
 * <p>리포지토리 목은 돌려줄 쿠폰·템플릿만 정한다. 쿠폰과 템플릿은 목이 아니라 빌더로 만든 진짜 엔티티라, 사용 여부·발급 수 같은
 * 결과를 엔티티 상태로 확인한다. 서비스가 "지금" 을 직접 읽으므로 기간은 지금 기준 상대 시각으로 둔다.
 *
 * <p>사용자 id 는 Long 캐시(-128~127) 밖의 값을 쓰고 박싱은 쓰는 곳마다 따로 한다. 쿠폰 소유자 id 와 요청자 id 가 값은 같고 객체는
 * 달라, 소유자 비교를 equals 대신 참조 비교로 바꾸면 본인 쿠폰도 거부되어 테스트가 실패한다.
 */
@ExtendWith(MockitoExtension.class)
class CouponServiceTest {

	private static final long OWNER_ID = 1000L;
	private static final long OTHER_USER_ID = 2000L;
	private static final Long COUPON_ID = 7L;
	private static final Long TEMPLATE_ID = 11L;
	private static final int ORIGINAL_AMOUNT = 10000;

	@Mock
	private CouponRepository couponRepository;
	@Mock
	private CouponTemplateRepository couponTemplateRepository;

	private CouponService couponService;

	@BeforeEach
	void setUp() {
		couponService = new CouponService(couponRepository, couponTemplateRepository);
	}

	@Nested
	@DisplayName("결제에 쓸 쿠폰을 검증하고 할인가를 계산할 때(validateAndCalculateCoupon)")
	class ValidateAndCalculateCoupon {

		@Test
		@DisplayName("본인의 미사용 쿠폰이면 쿠폰으로 계산한 할인가와 쿠폰 이름을 돌려준다")
		void returnsDiscountedAmountForOwnUnusedCoupon() {
			// given
			Coupon coupon = CouponFixture.fixedAmount(2000).id(COUPON_ID).userId(OWNER_ID).name("신규가입 쿠폰")
				.build();
			given(couponRepository.findByIdWithLock(COUPON_ID)).willReturn(Optional.of(coupon));

			// when
			DiscountValidationResult result = couponService.validateAndCalculateCoupon(COUPON_ID, OWNER_ID,
				ORIGINAL_AMOUNT);

			// then
			assertThat(result.getFinalAmount()).isEqualTo(8000);
			assertThat(result.getAppliedCode()).isEqualTo("신규가입 쿠폰");
			assertThat(result.getDiscountCodeEntity()).isNull();
		}

		@Test
		@DisplayName("다른 사용자의 쿠폰이면 '본인의 쿠폰만 사용할 수 있습니다.' 로 거절한다")
		void rejectsCouponOwnedByAnotherUser() {
			// given
			Coupon coupon = CouponFixture.usableCoupon().id(COUPON_ID).userId(OWNER_ID).build();
			given(couponRepository.findByIdWithLock(COUPON_ID)).willReturn(Optional.of(coupon));

			// when & then
			assertThatThrownBy(() -> couponService.validateAndCalculateCoupon(COUPON_ID, OTHER_USER_ID,
				ORIGINAL_AMOUNT))
				.isInstanceOf(PaymentException.class)
				.hasMessage("본인의 쿠폰만 사용할 수 있습니다.");
		}

		@Test
		@DisplayName("이미 사용한 쿠폰이면 '이미 사용한 쿠폰입니다.' 로 거절한다")
		void rejectsUsedCoupon() {
			// given
			Coupon coupon = CouponFixture.usableCoupon().id(COUPON_ID).userId(OWNER_ID)
				.usedAt(LocalDateTime.now().minusDays(1)).build();
			given(couponRepository.findByIdWithLock(COUPON_ID)).willReturn(Optional.of(coupon));

			// when & then
			assertThatThrownBy(() -> couponService.validateAndCalculateCoupon(COUPON_ID, OWNER_ID,
				ORIGINAL_AMOUNT))
				.isInstanceOf(PaymentException.class)
				.hasMessage("이미 사용한 쿠폰입니다.");
		}

		@Test
		@DisplayName("쿠폰이 없으면 '존재하지 않는 쿠폰입니다.' 로 거절한다")
		void rejectsMissingCoupon() {
			// given
			given(couponRepository.findByIdWithLock(COUPON_ID)).willReturn(Optional.empty());

			// when & then
			assertThatThrownBy(() -> couponService.validateAndCalculateCoupon(COUPON_ID, OWNER_ID,
				ORIGINAL_AMOUNT))
				.isInstanceOf(PaymentException.class)
				.hasMessage("존재하지 않는 쿠폰입니다.");
		}
	}

	@Nested
	@DisplayName("주문을 만들며 쿠폰을 사용 처리할 때(useCoupon)")
	class UseCoupon {

		@Test
		@DisplayName("만료 시각이 지난 쿠폰이면 '기간이 만료된 쿠폰입니다.' 로 거절하고 미사용으로 둔다")
		void rejectsExpiredCoupon() {
			// given
			Coupon coupon = CouponFixture.usableCoupon().id(COUPON_ID)
				.expiresAt(LocalDateTime.now().minusMinutes(1)).build();
			given(couponRepository.findByIdWithLock(COUPON_ID)).willReturn(Optional.of(coupon));

			// when & then
			assertThatThrownBy(() -> couponService.useCoupon(COUPON_ID))
				.isInstanceOf(PaymentException.class)
				.hasMessage("기간이 만료된 쿠폰입니다.");
			assertThat(coupon.isUsed()).isFalse();
		}
	}

	@Nested
	@DisplayName("주문이 쿠폰을 놓아 쿠폰을 되돌릴 때(restoreCoupon)")
	class RestoreCoupon {

		@Test
		@DisplayName("쿠폰 없이 만든 주문(쿠폰 id null)이면 쿠폰을 조회하지 않고 그대로 끝난다")
		void doesNothingForOrderWithoutCoupon() {
			// when & then
			assertThatCode(() -> couponService.restoreCoupon(null)).doesNotThrowAnyException();
			verifyNoInteractions(couponRepository);
		}
	}

	@Nested
	@DisplayName("쿠폰을 받을 때(downloadCoupon)")
	class DownloadCoupon {

		@Test
		@DisplayName("발급 기간 안이고 처음 받는 사용자면 그 사용자 몫의 쿠폰을 저장하고 발급 수를 1 올린다")
		void issuesCouponWithinIssuePeriod() {
			// given
			CouponTemplate template = CouponTemplateFixture.issuableNow().id(TEMPLATE_ID)
				.issuePeriod(LocalDateTime.now().minusHours(1), LocalDateTime.now().plusHours(1))
				.maxIssueCount(100).currentIssueCount(5).build();
			given(couponTemplateRepository.findByIdWithLock(TEMPLATE_ID)).willReturn(Optional.of(template));
			given(couponRepository.existsByUserIdAndTemplateId(OWNER_ID, TEMPLATE_ID)).willReturn(false);

			// when
			couponService.downloadCoupon(OWNER_ID, TEMPLATE_ID);

			// then
			ArgumentCaptor<Coupon> saved = ArgumentCaptor.forClass(Coupon.class);
			then(couponRepository).should().saveAndFlush(saved.capture());
			assertThat(saved.getValue().getUserId()).isEqualTo(OWNER_ID);
			assertThat(saved.getValue().getTemplateId()).isEqualTo(TEMPLATE_ID);
			assertThat(template.getCurrentIssueCount()).as("템플릿의 발급 수").isEqualTo(6);
		}

		@ParameterizedTest(name = "[{index}] 발급 기간이 지금부터 {0}시간 ~ {1}시간 → 거절")
		@CsvSource(textBlock = """
			# 발급 시작, 발급 끝 (지금 기준 시간. 음수는 과거)
			  1, 240
			-240,  -1
			""")
		@DisplayName("발급 기간 전이거나 끝난 뒤면 '발급 기간이 아닙니다.' 로 거절하고 쿠폰 저장도 발급 수 증가도 하지 않는다")
		void rejectsOutsideIssuePeriod(int startHoursFromNow, int endHoursFromNow) {
			// given
			CouponTemplate template = CouponTemplateFixture.issuableNow().id(TEMPLATE_ID)
				.issuePeriod(LocalDateTime.now().plusHours(startHoursFromNow),
					LocalDateTime.now().plusHours(endHoursFromNow))
				.maxIssueCount(100).currentIssueCount(5).build();
			given(couponTemplateRepository.findByIdWithLock(TEMPLATE_ID)).willReturn(Optional.of(template));

			// when & then
			assertThatThrownBy(() -> couponService.downloadCoupon(OWNER_ID, TEMPLATE_ID))
				.isInstanceOf(PaymentException.class)
				.hasMessage("발급 기간이 아닙니다.");
			assertThat(template.getCurrentIssueCount()).as("템플릿의 발급 수").isEqualTo(5);
			then(couponRepository).should(never()).saveAndFlush(any(Coupon.class));
		}

		@Test
		@DisplayName("선착순 상한만큼 이미 발급했으면 CouponSoldOutException('선착순 마감되었습니다.')으로 거절하고 쿠폰을 저장하지 않는다")
		void rejectsWhenSoldOut() {
			// given
			CouponTemplate template = CouponTemplateFixture.issuableNow().id(TEMPLATE_ID)
				.maxIssueCount(100).currentIssueCount(100).build();
			given(couponTemplateRepository.findByIdWithLock(TEMPLATE_ID)).willReturn(Optional.of(template));
			given(couponRepository.existsByUserIdAndTemplateId(OWNER_ID, TEMPLATE_ID)).willReturn(false);

			// when & then
			assertThatThrownBy(() -> couponService.downloadCoupon(OWNER_ID, TEMPLATE_ID))
				.isInstanceOf(CouponSoldOutException.class)
				.hasMessage("선착순 마감되었습니다.");
			assertThat(template.getCurrentIssueCount()).as("템플릿의 발급 수").isEqualTo(100);
			then(couponRepository).should(never()).saveAndFlush(any(Coupon.class));
		}
	}
}
