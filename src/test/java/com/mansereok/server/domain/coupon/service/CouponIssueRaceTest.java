package com.mansereok.server.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.coupon.entity.CouponTemplate;
import com.mansereok.server.domain.coupon.repository.CouponRepository;
import com.mansereok.server.domain.coupon.repository.CouponTemplateRepository;
import com.mansereok.server.global.exception.CouponSoldOutException;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.CouponTemplateFixture;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 선착순 100장 쿠폰에 서로 다른 사용자 200명이 한 순간에 몰릴 때, 템플릿 행 잠금이 있을 때와 없을 때의 발급 결과를 실제 MySQL 로
 * 비교한다.
 *
 * <p>잠금이 없으면 요청들이 같은 발급 수(예: 99)를 읽고 저마다 1 을 더해 같은 값(100)으로 덮어쓴다. 발급 수는 덮어쓰여 실제보다
 * 작게 남지만 쿠폰 행은 요청마다 하나씩 저장되므로, 초과 발급은 발급 수가 아니라 coupons 행을 세야 드러난다.
 *
 * <p>200개 요청이 DB 에서도 함께 돌도록 이 클래스만 커넥션 풀을 100 으로 키운다. 풀을 200 으로 두면 로컬 MySQL 의
 * max_connections 기본값(151)을 넘는다. 풀 크기가 달라 이 클래스는 스프링 컨텍스트를 따로 띄운다.
 */
@TestPropertySource(properties = "spring.datasource.hikari.maximum-pool-size=100")
class CouponIssueRaceTest extends PaymentMySqlTest {

	private static final int USER_COUNT = 200;
	private static final int ISSUE_LIMIT = 100;

	@Autowired
	private CouponService couponService;
	@Autowired
	private CouponTemplateRepository couponTemplateRepository;
	@Autowired
	private CouponRepository couponRepository;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	// 요청 번호(0~199)를 더해 요청마다 다른 사용자 id 를 만든다.
	private final long firstUserId = Long.parseLong(runId, 16) * 1000;
	private final List<Long> createdTemplateIds = new ArrayList<>();

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		for (Long templateId : createdTemplateIds) {
			jdbcTemplate.update("DELETE FROM coupons WHERE template_id = ?", templateId);
			jdbcTemplate.update("DELETE FROM coupon_templates WHERE id = ?", templateId);
		}
	}

	@RepeatedTest(value = 3, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("템플릿 행을 잠그지 않고 받으면 200명 중 100명보다 많이 받아 쿠폰 행이 100장을 넘고, 발급 수는 쿠폰 행보다 적게 남는다")
	void withoutLockIssuesMoreThanLimit() {
		// given
		Long templateId = saveTemplateWithIssueLimit(ISSUE_LIMIT);

		// when
		List<CallResult<Void>> results = ConcurrentCalls.runAtTheSameTime(USER_COUNT, index -> () -> {
			downloadWithoutLock(firstUserId + index, templateId);
			return null;
		});

		// then
		int couponCount = couponCount(templateId);
		int issueCount = issueCount(templateId);
		long succeeded = results.stream().filter(CallResult::succeeded).count();
		String summary = "성공 " + succeeded + "건, 쿠폰 행 " + couponCount + "장, 발급 수 " + issueCount;
		System.out.println("[잠금 없음] " + summary);

		assertThat(couponCount).as(summary).isGreaterThan(ISSUE_LIMIT);
		assertThat(issueCount).as(summary).isLessThan(couponCount);
	}

	@RepeatedTest(value = 3, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("CouponService.downloadCoupon 으로 받으면 200명 중 100명만 받고 100명은 '선착순 마감되었습니다.' 로 거절되며, 쿠폰 100행과 발급 수 100 이 남는다")
	void withLockIssuesExactlyLimit() {
		// given
		Long templateId = saveTemplateWithIssueLimit(ISSUE_LIMIT);

		// when
		List<CallResult<Void>> results = ConcurrentCalls.runAtTheSameTime(USER_COUNT, index -> () -> {
			couponService.downloadCoupon(firstUserId + index, templateId);
			return null;
		});

		// then
		int couponCount = couponCount(templateId);
		int issueCount = issueCount(templateId);
		long succeeded = results.stream().filter(CallResult::succeeded).count();
		String summary = "성공 " + succeeded + "건, 쿠폰 행 " + couponCount + "장, 발급 수 " + issueCount;
		System.out.println("[잠금 있음] " + summary);

		String resultsPerRequest = ConcurrentCalls.describe(results);
		assertThat(results).filteredOn(CallResult::succeeded)
			.as("쿠폰을 받은 요청. %s", resultsPerRequest)
			.hasSize(ISSUE_LIMIT);
		assertThat(results).filteredOn(result -> !result.succeeded())
			.as("거절된 요청. %s", resultsPerRequest)
			.hasSize(USER_COUNT - ISSUE_LIMIT)
			.allSatisfy(result -> assertThat(result.error())
				.isExactlyInstanceOf(CouponSoldOutException.class)
				.hasMessage("선착순 마감되었습니다."));
		assertThat(couponCount).as(summary).isEqualTo(ISSUE_LIMIT);
		assertThat(issueCount).as(summary).isEqualTo(ISSUE_LIMIT);
	}

	/**
	 * CouponService.downloadCoupon 에서 템플릿 행 잠금(findByIdWithLock)만 잠그지 않는 findById 로 바꾼 것. 잠금이 없을 때를
	 * 재현하려고 테스트에만 둔다. 발급 기간과 이미 받았는지 확인은 사용자가 모두 달라 결과에 영향이 없으므로 뺐다.
	 */
	private void downloadWithoutLock(Long userId, Long templateId) {
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			CouponTemplate template = couponTemplateRepository.findById(templateId).orElseThrow();
			template.incrementIssueCount();
			couponRepository.saveAndFlush(Coupon.createFromTemplate(template, userId, LocalDateTime.now()));
		});
	}

	private Long saveTemplateWithIssueLimit(int maxIssueCount) {
		Long templateId = couponTemplateRepository.saveAndFlush(CouponTemplateFixture.issuableNow().withoutId()
				.name("선착순 경쟁 확인 쿠폰 " + runId).maxIssueCount(maxIssueCount).build())
			.getId();
		createdTemplateIds.add(templateId);
		return templateId;
	}

	private int couponCount(Long templateId) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupons WHERE template_id = ?", Integer.class,
			templateId);
	}

	private int issueCount(Long templateId) {
		return jdbcTemplate.queryForObject("SELECT current_issue_count FROM coupon_templates WHERE id = ?",
			Integer.class, templateId);
	}
}
