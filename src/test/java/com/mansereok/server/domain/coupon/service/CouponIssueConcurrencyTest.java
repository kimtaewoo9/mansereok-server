package com.mansereok.server.domain.coupon.service;

import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.coupon.repository.CouponTemplateRepository;
import com.mansereok.server.global.exception.CouponSoldOutException;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.CouponTemplateFixture;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 선착순 쿠폰 받기에 사람이 몰려도 상한보다 많이 발급되지 않고, 한 사용자에게는 한 장만 나가는지 실제 MySQL 로 확인한다.
 *
 * <p>쿠폰 받기는 템플릿 행을 잠가(CouponTemplateRepository.findByIdWithLock) 읽은 뒤 이미 받았는지 확인하고, 발급 수를 올리고,
 * 쿠폰을 저장한다. 이 잠금이 없으면 요청들이 같은 발급 수를 읽고 저마다 1 을 더해, 상한보다 많은 쿠폰이 저장되고 발급 수는 서로
 * 덮어써 실제보다 작게 남는다. 잠금은 DB 가 지키는 규칙이라 서비스를 여러 스레드에서 한 순간에 불러 확인한다. 어느 요청이 먼저
 * 잠금을 잡을지는 매번 달라서 여러 번 되풀이한다.
 *
 * <p>쿠폰은 사용자 id 만 들고 users 표를 참조하지 않으므로, 실행 키에서 만든 id 를 그대로 쓴다. 템플릿과 쿠폰은 이번 실행에서 만든
 * 템플릿 id 로만 지운다.
 */
class CouponIssueConcurrencyTest extends PaymentMySqlTest {

	// HikariCP 기본 최대 커넥션 수(10)를 넘기지 않는다. 넘기면 잠금이 아니라 커넥션을 기다리는 시간을 재게 된다.
	private static final int REQUEST_COUNT = 10;

	@Autowired
	private CouponService couponService;
	@Autowired
	private CouponTemplateRepository couponTemplateRepository;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다. @RepeatedTest 는 되풀이마다 새 인스턴스를 만든다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	// 요청 번호(0~9)를 더해 요청마다 다른 사용자 id 를 만든다.
	private final long firstUserId = Long.parseLong(runId, 16) * 100;
	private final List<Long> createdTemplateIds = new ArrayList<>();

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		for (Long templateId : createdTemplateIds) {
			jdbcTemplate.update("DELETE FROM coupons WHERE template_id = ?", templateId);
			jdbcTemplate.update("DELETE FROM coupon_templates WHERE id = ?", templateId);
		}
	}

	@RepeatedTest(value = 5, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("선착순 3장 이벤트에 서로 다른 사용자 10명이 동시에 받으면 3명만 받고 7명은 '선착순 마감되었습니다.' 로 거절되며, 쿠폰 3행과 발급 수 3 이 남는다")
	void tenUsersRaceForThreeCoupons() {
		// given
		Long templateId = saveTemplateWithIssueLimit(3);

		// when
		List<CallResult<Void>> results = ConcurrentCalls.runAtTheSameTime(REQUEST_COUNT, index -> () -> {
			couponService.downloadCoupon(firstUserId + index, templateId);
			return null;
		});

		// then: 요청마다 결과를 확인한다
		String resultsPerRequest = describe(results);
		assertThat(results).filteredOn(CallResult::succeeded)
			.as("쿠폰을 받은 요청. %s", resultsPerRequest)
			.hasSize(3);
		assertThat(results).filteredOn(result -> !result.succeeded())
			.as("거절된 요청. %s", resultsPerRequest)
			.hasSize(7)
			.allSatisfy(result -> assertThat(result.error())
				.isExactlyInstanceOf(CouponSoldOutException.class)
				.hasMessage("선착순 마감되었습니다."));

		// then: DB 에 남은 사실은 JPA 캐시를 거치지 않고 SQL 로 센다
		assertThat(couponCount(templateId)).as("쿠폰 행. %s", resultsPerRequest).isEqualTo(3);
		assertThat(issueCount(templateId)).as("발급 수. %s", resultsPerRequest).isEqualTo(3);
	}

	@RepeatedTest(value = 5, name = "{displayName} ({currentRepetition}/{totalRepetitions})")
	@DisplayName("한 사용자가 같은 이벤트 쿠폰을 동시에 10번 받으면 1번만 받고 9번은 '이미 발급받은 쿠폰입니다.' 로 거절되며, 쿠폰 1행과 발급 수 1 이 남는다")
	void sameUserRequestsTenTimesAtOnce() {
		// given: 선착순 상한이 없는 이벤트라 1인 1장 규칙만 요청을 거른다
		Long templateId = saveTemplateWithIssueLimit(null);

		// when
		List<CallResult<Void>> results = ConcurrentCalls.runAtTheSameTime(REQUEST_COUNT, () -> {
			couponService.downloadCoupon(firstUserId, templateId);
			return null;
		});

		// then
		String resultsPerRequest = describe(results);
		assertThat(results).filteredOn(CallResult::succeeded)
			.as("쿠폰을 받은 요청. %s", resultsPerRequest)
			.hasSize(1);
		assertThat(results).filteredOn(result -> !result.succeeded())
			.as("거절된 요청. %s", resultsPerRequest)
			.hasSize(9)
			.allSatisfy(result -> assertThat(result.error())
				.isExactlyInstanceOf(PaymentException.class)
				.hasMessage("이미 발급받은 쿠폰입니다."));

		// then
		assertThat(couponCount(templateId)).as("쿠폰 행. %s", resultsPerRequest).isEqualTo(1);
		assertThat(issueCount(templateId)).as("발급 수. %s", resultsPerRequest).isEqualTo(1);
	}

	/** 지금 받을 수 있는 1인 1장 쿠폰 이벤트를 저장한다. maxIssueCount 가 null 이면 선착순 상한이 없다. */
	private Long saveTemplateWithIssueLimit(Integer maxIssueCount) {
		Long templateId = couponTemplateRepository.saveAndFlush(CouponTemplateFixture.issuableNow().withoutId()
				.name("동시 받기 확인 쿠폰 " + runId).maxIssueCount(maxIssueCount).build())
			.getId();
		createdTemplateIds.add(templateId);
		return templateId;
	}

	private Integer couponCount(Long templateId) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM coupons WHERE template_id = ?", Integer.class,
			templateId);
	}

	private Integer issueCount(Long templateId) {
		return jdbcTemplate.queryForObject("SELECT current_issue_count FROM coupon_templates WHERE id = ?",
			Integer.class, templateId);
	}

	/** 실패 메시지에 넣을 요청별 결과. 예: "요청별 결과 [성공, CouponSoldOutException(선착순 마감되었습니다.), ...]" */
	private static String describe(List<? extends CallResult<?>> results) {
		return results.stream()
			.map(result -> result.succeeded() ? "성공"
				: result.error().getClass().getSimpleName() + "(" + result.error().getMessage() + ")")
			.collect(joining(", ", "요청별 결과 [", "]"));
	}
}
