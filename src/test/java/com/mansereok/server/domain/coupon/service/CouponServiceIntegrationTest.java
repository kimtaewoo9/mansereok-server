package com.mansereok.server.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.coupon.dto.CouponEventDto;
import com.mansereok.server.domain.discount.entity.DiscountType;
import com.mansereok.server.support.LocalMySqlTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

/**
 * 쿠폰 이벤트 목록, 쿠폰 받기, 내 쿠폰함을 실제 MySQL 로 확인한다. 목록 쿼리가 LEFT JOIN 과 DB 시각(CURRENT_TIMESTAMP)을
 * 쓰므로 목으로는 확인할 수 없다.
 *
 * <p>같은 스키마에 다른 테스트와 이전 실행이 남긴 쿠폰이 있을 수 있다. 그래서 템플릿은 이번 실행에서 JdbcTemplate 로 만들고,
 * 목록은 그 템플릿 id 로 걸러서 확인하며, 뒤 정리에서도 그 id 로 만든 행만 지운다.
 */
class CouponServiceIntegrationTest extends LocalMySqlTest {

	private static final DateTimeFormatter VALID_PERIOD_DATE = DateTimeFormatter.ofPattern("yyyy.MM.dd");

	@Autowired
	private CouponService couponService;

	// 실행마다 다른 값이라 이전 실행이 남긴 데이터와 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	// 쿠폰은 사용자 id 만 들고 users 표를 참조하지 않으므로, 실행 키에서 만든 id 를 그대로 쓴다.
	private final Long userId = Long.parseLong(runId, 16);
	private final List<Long> createdTemplateIds = new ArrayList<>();

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		for (Long templateId : createdTemplateIds) {
			jdbcTemplate.update("DELETE FROM coupons WHERE template_id = ?", templateId);
			jdbcTemplate.update("DELETE FROM coupon_templates WHERE id = ?", templateId);
		}
	}

	@Test
	@DisplayName("쿠폰 이벤트 목록은 할인 종류와 유효 기간 문구를 보여 주고, 받지 않은 쿠폰은 받지 않음으로 표시한다")
	void listsCouponEventsWithValidPeriod() {
		// given
		Long validForDaysTemplateId = insertTemplate("신규회원 쿠폰", DiscountType.PERCENTAGE, 10, 30, null);
		Long validUntilTemplateId = insertTemplate("마감임박 쿠폰", DiscountType.FIXED_AMOUNT, 1000, null,
			LocalDateTime.of(2030, 12, 31, 23, 59));
		LocalDate dateBeforeCall = LocalDate.now();

		// when
		List<CouponEventDto> events = couponService.getCouponEvents(userId);

		// then
		LocalDate dateAfterCall = LocalDate.now();
		assertThat(events)
			.filteredOn(event -> validForDaysTemplateId.equals(event.getTemplateId()))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.getDiscountType()).isEqualTo("PERCENTAGE");
				assertThat(event.isIssued()).isFalse();
				// 서비스는 호출한 순간의 날짜 + 30일을 보여 준다. 자정을 넘기는 실행에서도 흔들리지 않게 호출 전후 날짜를 모두 받아들인다.
				assertThat(event.getValidPeriod()).isIn(
					dateBeforeCall.plusDays(30).format(VALID_PERIOD_DATE) + " 까지",
					dateAfterCall.plusDays(30).format(VALID_PERIOD_DATE) + " 까지");
			});
		assertThat(events)
			.filteredOn(event -> validUntilTemplateId.equals(event.getTemplateId()))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.getDiscountType()).isEqualTo("FIXED_AMOUNT");
				assertThat(event.getValidPeriod()).isEqualTo("2030.12.31 까지");
			});
	}

	@Test
	@DisplayName("쿠폰을 받으면 내 쿠폰함에 보이고, 이벤트 목록에서는 받음으로 표시된다")
	void downloadedCouponAppearsInMyCoupons() {
		// given
		Long templateId = insertTemplate("다운로드 테스트 쿠폰", DiscountType.FIXED_AMOUNT, 5000, 7, null);

		// when
		couponService.downloadCoupon(userId, templateId);

		// then
		assertThat(couponService.getMyCoupons(userId))
			.singleElement()
			.satisfies(coupon -> {
				assertThat(coupon.getTemplateId()).isEqualTo(templateId);
				assertThat(coupon.getName()).isEqualTo("다운로드 테스트 쿠폰 " + runId);
				assertThat(coupon.getDiscountValue()).isEqualTo(5000);
			});
		assertThat(couponService.getCouponEvents(userId))
			.filteredOn(event -> templateId.equals(event.getTemplateId()))
			.singleElement()
			.satisfies(event -> assertThat(event.isIssued()).isTrue());
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM coupons WHERE template_id = ? AND user_id = ?", Integer.class, templateId,
			userId))
			.as("DB 에 남은 쿠폰 행").isEqualTo(1);
	}

	/**
	 * 어제부터 10일 뒤까지 받을 수 있는 1인 1장 쿠폰 템플릿을 만든다. 이름 끝에 실행 키를 붙여 남은 행을 알아볼 수 있게 한다.
	 */
	private Long insertTemplate(String name, DiscountType discountType, int discountValue,
		Integer validDaysAfterIssue, LocalDateTime validUntil) {
		Map<String, Object> row = new HashMap<>();
		row.put("name", name + " " + runId);
		row.put("discount_type", discountType.name());
		row.put("discount_value", discountValue);
		row.put("min_purchase_amount", 0);
		row.put("issue_start_date", LocalDateTime.now().minusDays(1));
		row.put("issue_end_date", LocalDateTime.now().plusDays(10));
		row.put("valid_days_after_issue", validDaysAfterIssue);
		row.put("valid_until", validUntil);
		row.put("max_count_per_user", 1);
		Long templateId = new SimpleJdbcInsert(jdbcTemplate)
			.withTableName("coupon_templates")
			.usingColumns(row.keySet().toArray(String[]::new))
			.usingGeneratedKeyColumns("id")
			.executeAndReturnKey(row)
			.longValue();
		createdTemplateIds.add(templateId);
		return templateId;
	}
}
