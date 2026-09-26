package com.mansereok.server.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.mansereok.server.domain.coupon.dto.CouponEventDto;
import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.coupon.entity.CouponTemplate;
import com.mansereok.server.domain.coupon.repository.CouponRepository;
import com.mansereok.server.domain.coupon.repository.CouponTemplateRepository;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.fixture.CouponTemplateFixture;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Optional;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 쿠폰 받기가 쿠폰 저장에서 난 DB 제약 위반을 어떻게 돌려주는지와, 이벤트 목록의 마감 표시를 확인한다.
 *
 * <p>리포지토리 목은 돌려줄 값과 던질 예외만 정한다. 저장 예외는 Hibernate MySQL 방언과 스프링이 실제로 만드는 모양대로 손으로
 * 만든다. 중복 키(1062)는 ConstraintKind.UNIQUE 를 담고, NOT NULL 위반(1048)은 종류를 적지 않는다. 실제 MySQL 이 이 모양을
 * 만드는지는 CouponUniqueMySqlTest 가 본다.
 */
@ExtendWith(MockitoExtension.class)
class CouponDownloadTest {

	private static final Long USER_ID = 3L;
	private static final Long TEMPLATE_ID = 11L;

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
	@DisplayName("아직 받지 않았다고 확인한 뒤 쿠폰 저장이 DB 제약에 걸리면")
	class WhenSavingIssuedCouponViolatesConstraint {

		private CouponTemplate template;

		@BeforeEach
		void givenTemplateNotYetIssuedToUser() {
			template = CouponTemplateFixture.issuableNow().id(TEMPLATE_ID)
				.maxIssueCount(100).currentIssueCount(5).build();
			given(couponTemplateRepository.findByIdWithLock(TEMPLATE_ID)).willReturn(Optional.of(template));
			given(couponRepository.existsByUserIdAndTemplateId(USER_ID, TEMPLATE_ID)).willReturn(false);
		}

		@Test
		@DisplayName("(사용자, 템플릿) UNIQUE 위반이면 먼저 확인했을 때와 같은 '이미 발급받은 쿠폰입니다.' 결제 예외로 바꿔 던지고 원인을 잇는다")
		void uniqueViolationBecomesAlreadyIssued() {
			// given
			DataIntegrityViolationException duplicate = violation(
				new SQLIntegrityConstraintViolationException(
					"Duplicate entry '3-11' for key 'coupons.uk_coupons_user_template'", "23000", 1062),
				ConstraintKind.UNIQUE, "coupons.uk_coupons_user_template");
			given(couponRepository.saveAndFlush(any(Coupon.class))).willThrow(duplicate);

			// when & then
			assertThatThrownBy(() -> couponService.downloadCoupon(USER_ID, TEMPLATE_ID))
				.isExactlyInstanceOf(PaymentException.class)
				.hasMessage("이미 발급받은 쿠폰입니다.")
				.cause().isSameAs(duplicate);
		}

		@Test
		@DisplayName("NOT NULL 처럼 UNIQUE 가 아닌 위반이면 '이미 받았다' 로 바꾸지 않고 받은 예외를 그대로 던진다")
		void otherViolationIsRethrownAsIs() {
			// given
			DataIntegrityViolationException notNull = violation(
				new SQLIntegrityConstraintViolationException("Column 'user_id' cannot be null", "23000", 1048),
				ConstraintKind.OTHER, null);
			given(couponRepository.saveAndFlush(any(Coupon.class))).willThrow(notNull);

			// when & then
			assertThatThrownBy(() -> couponService.downloadCoupon(USER_ID, TEMPLATE_ID))
				.isSameAs(notNull);
		}
	}

	@Nested
	@DisplayName("쿠폰 이벤트 목록의 마감 표시는")
	class SoldOutFlagInEvents {

		@ParameterizedTest(name = "[{index}] 상한 {0}, 발급 수 {1} → 마감 {2}")
		@CsvSource(textBlock = """
			# 선착순 상한(빈 칸은 무제한), 발급 수, 마감 표시
			 100,   0, false
			 100, 100, true
			    ,   5, false
			""")
		@DisplayName("템플릿의 발급 수와 상한으로 정한다. 아직 한 장도 발급하지 않은 템플릿(발급 수 0)도 목록에 마감 아님으로 뜬다")
		void followsTemplateIssueCount(Integer maxIssueCount, int currentIssueCount, boolean expected) {
			// given
			CouponTemplate template = CouponTemplateFixture.issuableNow().id(TEMPLATE_ID)
				.maxIssueCount(maxIssueCount).currentIssueCount(currentIssueCount).build();
			List<Object[]> rows = List.<Object[]>of(new Object[] {template, false});
			given(couponTemplateRepository.findAllWithIssueStatus(USER_ID)).willReturn(rows);

			// when
			List<CouponEventDto> events = couponService.getCouponEvents(USER_ID);

			// then
			assertThat(events).singleElement()
				.satisfies(event -> {
					assertThat(event.getTemplateId()).isEqualTo(TEMPLATE_ID);
					assertThat(event.isSoldOut()).isEqualTo(expected);
				});
		}
	}

	/** 스프링이 Hibernate 제약 위반을 번역한 모양 그대로: DataIntegrityViolationException(cause = ConstraintViolationException). */
	private static DataIntegrityViolationException violation(SQLIntegrityConstraintViolationException sqlException,
		ConstraintKind kind, String constraintName) {
		ConstraintViolationException hibernateException = new ConstraintViolationException(
			"could not execute statement", sqlException, "insert into coupons (user_id, template_id) values (?, ?)",
			kind, constraintName);
		return new DataIntegrityViolationException(hibernateException.getMessage(), hibernateException);
	}
}
