package com.mansereok.server.domain.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mansereok.server.domain.coupon.dto.CouponEventDto;
import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.support.PaymentMySqlTest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

/**
 * 쿠폰 템플릿의 발급 수(current_issue_count)가 실제 MySQL 에서 NOT NULL DEFAULT 0 으로 걸려 있는지 확인한다.
 *
 * <p>엔티티는 발급 수를 int 로 읽으므로 NULL 인 행이 있으면 그 템플릿을 읽는 순간 실패한다. 템플릿은 운영자가 SQL 로 넣기 때문에,
 * 칸을 비워 넣어도 0 이 들어가고 NULL 은 들어가지 않는다는 DB 규칙이 이 int 를 받쳐 준다. 그래서 운영자가 넣는 방식 그대로
 * JdbcTemplate 로 넣어 본다.
 *
 * <p>ddl-auto: update 는 이미 있는 컬럼의 NULL 허용과 DEFAULT 를 바꾸지 않는다. 이 변경 전에 만든 테스트 DB 라면 운영과 같은 DDL 을
 * 손으로 적용하거나 coupon_templates 를 지우고 다시 돌린다. 새로 만드는 표에는 엔티티의 @ColumnDefault 로 DEFAULT 0 이 걸린다.
 */
class CouponTemplateIssueCountMySqlTest extends PaymentMySqlTest {

	private static final String OLD_TEST_DATABASE_HINT = "이 변경 전에 만든 테스트 DB 라면 운영과 같은 DDL(UPDATE coupon_templates SET"
		+ " current_issue_count = 0 WHERE current_issue_count IS NULL; ALTER TABLE coupon_templates MODIFY"
		+ " current_issue_count INT NOT NULL DEFAULT 0)을 적용하고 다시 돌린다.";

	@Autowired
	private CouponService couponService;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	// 쿠폰은 사용자 id 만 들고 users 표를 참조하지 않으므로, 실행 키에서 만든 id 를 그대로 쓴다.
	private final Long userId = Long.parseLong(runId, 16);
	private final List<Long> createdTemplateIds = new ArrayList<>();

	@AfterEach
	void deleteTemplatesOfThisRun() {
		for (Long templateId : createdTemplateIds) {
			jdbcTemplate.update("DELETE FROM coupon_templates WHERE id = ?", templateId);
		}
	}

	@Test
	@DisplayName("발급 수 칸을 비워 넣으면 0 으로 저장된다")
	void issueCountDefaultsToZero() {
		// when: 운영자가 넣는 것처럼 발급 수 칸을 빼고 넣는다
		Long templateId = insert(templateRow());

		// then
		assertThat(jdbcTemplate.queryForObject("SELECT current_issue_count FROM coupon_templates WHERE id = ?",
			Integer.class, templateId))
			.as("비워 넣은 발급 수. " + OLD_TEST_DATABASE_HINT)
			.isEqualTo(0);
	}

	@Test
	@DisplayName("발급 수 칸을 비워 넣은 선착순 템플릿도 이벤트 목록에 마감 아님으로 뜬다")
	void templateInsertedWithoutIssueCountIsListedAsNotSoldOut() {
		// given: 선착순 100장, 발급 수 칸은 넣지 않는다
		Map<String, Object> row = templateRow();
		row.put("max_issue_count", 100);
		Long templateId = insert(row);

		// when
		List<CouponEventDto> events = couponService.getCouponEvents(userId);

		// then
		assertThat(events)
			.filteredOn(event -> templateId.equals(event.getTemplateId()))
			.singleElement()
			.satisfies(event -> assertThat(event.isSoldOut()).isFalse());
	}

	@Test
	@DisplayName("발급 수에 NULL 을 넣으면 DB 가 거절한다")
	void nullIssueCountIsRejected() {
		// given
		Map<String, Object> row = templateRow();
		row.put("current_issue_count", null);

		// when
		Throwable thrown = catchThrowable(() -> insert(row));

		// then
		assertThat(thrown).as("NULL 발급 수가 들어갔다. " + OLD_TEST_DATABASE_HINT)
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("current_issue_count");
	}

	/** 지금 받을 수 있는 1인 1장 정액 쿠폰 템플릿 한 행. 이름 끝에 실행 키를 붙여 남은 행을 알아볼 수 있게 한다. */
	private Map<String, Object> templateRow() {
		Map<String, Object> row = new HashMap<>();
		row.put("name", "발급 수 확인 쿠폰 " + runId);
		row.put("discount_type", "FIXED_AMOUNT");
		row.put("discount_value", 1000);
		row.put("min_purchase_amount", 0);
		row.put("issue_start_date", LocalDateTime.now().minusDays(1));
		row.put("issue_end_date", LocalDateTime.now().plusDays(10));
		row.put("valid_days_after_issue", 30);
		row.put("max_count_per_user", 1);
		return row;
	}

	private Long insert(Map<String, Object> row) {
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
