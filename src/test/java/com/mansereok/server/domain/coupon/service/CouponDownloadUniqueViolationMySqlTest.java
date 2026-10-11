package com.mansereok.server.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.BDDMockito.willReturn;

import com.mansereok.server.domain.coupon.repository.CouponRepository;
import com.mansereok.server.domain.coupon.repository.CouponTemplateRepository;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.CouponTemplateFixture;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * 쿠폰 받기가 이미 받았는지 확인을 지나쳐 쿠폰 저장에서 uk_coupons_user_template 에 걸렸을 때, 실제 MySQL 에서 무엇을 답하고 DB 에
 * 무엇을 남기는지 확인한다.
 *
 * <p>쿠폰 받기는 템플릿 행을 잠근 채 이미 받았는지 먼저 확인하므로, 서비스를 그대로 부르면 두 번째 받기는 그 확인에서 멈추고 저장까지
 * 가지 않는다. 그래서 CouponRepository 를 스파이로 바꾸고 이미 받았는지 확인(existsByUserIdAndTemplateId)만 false 를 돌려주게 해,
 * 템플릿 잠금을 거치지 않은 경로로 쿠폰이 먼저 들어가 있던 상황을 만든다. 스파이의 나머지 메서드는 진짜 리포지토리를 그대로 부른다.
 *
 * <p>CouponDownloadTest 는 손으로 만든 예외로 같은 판정을 보고, CouponUniqueMySqlTest 는 서비스를 거치지 않고 리포지토리로 두 번
 * 저장한다. 이 테스트는 MySQL 이 실제로 던진 중복 키 예외가 서비스의 판정을 거쳐 "이미 발급받은 쿠폰입니다." 가 되는지와, 그 예외로
 * 앞에서 올린 발급 수까지 롤백되는지 본다.
 *
 * <p>스파이를 두면 목 조합이 다른 MySQL 테스트와 달라져 스프링이 이 테스트용 컨텍스트를 따로 띄운다. 로컬 MySQL 커넥션을 적게 쓰도록
 * 풀을 2개로 줄인다(쿠폰 받기 하나와 결과를 세는 조회면 충분하다).
 *
 * <p>템플릿과 쿠폰은 이번 실행에서 만든 템플릿 id 로만 지운다.
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=2")
class CouponDownloadUniqueViolationMySqlTest extends PaymentMySqlTest {

	private static final String ALREADY_ISSUED_MESSAGE = "이미 발급받은 쿠폰입니다.";

	@MockitoSpyBean
	private CouponRepository couponRepository;
	@Autowired
	private CouponTemplateRepository couponTemplateRepository;
	@Autowired
	private CouponService couponService;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	// 쿠폰은 사용자 id 만 들고 users 표를 참조하지 않으므로, 실행 키에서 만든 id 를 그대로 쓴다.
	private final Long userId = Long.parseLong(runId, 16);

	private Long templateId;

	@BeforeEach
	void saveTemplate() {
		templateId = couponTemplateRepository.saveAndFlush(
				CouponTemplateFixture.issuableNow().withoutId().name("UNIQUE 위반 받기 확인 쿠폰 " + runId).build())
			.getId();
	}

	@AfterEach
	void deleteRowsOfThisRun() {
		jdbcTemplate.update("DELETE FROM coupons WHERE template_id = ?", templateId);
		jdbcTemplate.update("DELETE FROM coupon_templates WHERE id = ?", templateId);
	}

	@Nested
	@DisplayName("이미 받은 사용자의 두 번째 받기가 이미 받았는지 확인을 지나쳐 쿠폰 저장에서 uk_coupons_user_template 에 걸리면")
	class WhenSecondCouponHitsUniqueKey {

		@BeforeEach
		void givenUserAlreadyHasCouponAndCheckSaysNotYet() {
			couponService.downloadCoupon(userId, templateId);
			willReturn(false).given(couponRepository).existsByUserIdAndTemplateId(userId, templateId);
		}

		@Test
		@DisplayName("'이미 발급받은 쿠폰입니다.' 결제 예외로 답하고 원인에 MySQL 의 UNIQUE 위반을 잇는다")
		void answersAlreadyIssuedWithUniqueViolationAsCause() {
			// when
			Throwable thrown = catchThrowable(() -> couponService.downloadCoupon(userId, templateId));

			// then: 먼저 확인한 곳에서 던진 예외는 원인이 없다. 원인이 있으면 저장에서 UNIQUE 에 걸려 바꾼 예외다
			assertThat(thrown).isExactlyInstanceOf(PaymentException.class).hasMessage(ALREADY_ISSUED_MESSAGE);
			assertThat(thrown.getCause()).as("쿠폰 저장에서 걸린 DB 예외")
				.isInstanceOfSatisfying(DataIntegrityViolationException.class, cause ->
					assertThat(cause.getMostSpecificCause()).as("MySQL 이 알려 준 제약 이름")
						.hasMessageContaining("uk_coupons_user_template"));
		}

		@Test
		@DisplayName("앞에서 올린 발급 수도 함께 롤백되어 쿠폰 1행과 발급 수 1 만 남는다")
		void rollsBackRaisedIssueCount() {
			// when: 던진 예외는 위 테스트가 본다
			catchThrowable(() -> couponService.downloadCoupon(userId, templateId));

			// then
			assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM coupons WHERE user_id = ? AND template_id = ?", Integer.class, userId,
				templateId))
				.as("이 사용자·템플릿의 쿠폰 행")
				.isEqualTo(1);
			assertThat(jdbcTemplate.queryForObject(
				"SELECT current_issue_count FROM coupon_templates WHERE id = ?", Integer.class, templateId))
				.as("템플릿의 발급 수")
				.isEqualTo(1);
		}
	}
}
