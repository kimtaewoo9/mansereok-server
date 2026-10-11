package com.mansereok.server.domain.coupon.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.coupon.entity.CouponTemplate;
import com.mansereok.server.global.exception.UniqueConstraintViolations;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.CouponTemplateFixture;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * coupons 의 (user_id, template_id) UNIQUE 가 실제 MySQL 에서 기대대로 걸리고 동작하는지 확인한다.
 *
 * <p>쿠폰 받기는 템플릿 행을 잠근 채 이미 받았는지 먼저 확인한다. 이 테스트는 그 잠금과 확인을 거치지 않고 쿠폰 행을 바로 두 번
 * 저장해, 마지막으로 DB 가 막는지와 그 예외를 UNIQUE 위반으로 판별하는지 본다. 쿠폰 받기는 이 판별로 UNIQUE 위반만 "이미 발급받은
 * 쿠폰입니다." 로 바꾼다. 쿠폰 받기를 거쳐 그 답과 발급 수 롤백까지 이어지는지는 CouponDownloadUniqueViolationMySqlTest 가 본다.
 *
 * <p>테스트 DB 는 ddl-auto: update 라 엔티티 선언(@Table)대로 UNIQUE 가 생긴다. 운영은 validate 라 같은 이름의 DDL 을 손으로
 * 적용한다. 그래서 여기서 보는 이름과 컬럼 순서가 운영에 적용할 DDL 과 schema.sql 의 기준이 된다. 엔티티와 schema.sql 이 그 이름을
 * 쓰는지는 PaymentSchemaSqlTest 가 DB 없이 본다.
 *
 * <p>템플릿과 쿠폰은 이번 실행에서 만든 템플릿 id 로만 지운다.
 */
class CouponUniqueMySqlTest extends PaymentMySqlTest {

	// information_schema.STATISTICS.NON_UNIQUE 값. 0 이면 UNIQUE 다.
	private static final int UNIQUE = 0;
	private static final LocalDateTime ISSUED_AT = LocalDateTime.of(2026, 9, 1, 10, 0);

	@Autowired
	private CouponRepository couponRepository;
	@Autowired
	private CouponTemplateRepository couponTemplateRepository;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	// 쿠폰은 사용자 id 만 들고 users 표를 참조하지 않으므로, 실행 키에서 만든 id 를 그대로 쓴다.
	private final Long userId = Long.parseLong(runId, 16);

	private CouponTemplate template;

	@BeforeEach
	void saveTemplate() {
		template = couponTemplateRepository.saveAndFlush(
			CouponTemplateFixture.issuableNow().withoutId().name("UNIQUE 확인 쿠폰 " + runId).build());
	}

	@AfterEach
	void deleteRowsOfThisRun() {
		jdbcTemplate.update("DELETE FROM coupons WHERE template_id = ?", template.getId());
		jdbcTemplate.update("DELETE FROM coupon_templates WHERE id = ?", template.getId());
	}

	@Test
	@DisplayName("uk_coupons_user_template 가 (user_id, template_id) 순서의 UNIQUE 로 테스트 DB 에 있다")
	void uniqueKeyExistsWithColumnsInOrder() {
		// when
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
			"SELECT COLUMN_NAME, NON_UNIQUE FROM information_schema.STATISTICS"
				+ " WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'coupons'"
				+ " AND INDEX_NAME = 'uk_coupons_user_template' ORDER BY SEQ_IN_INDEX");

		// then
		assertThat(rows)
			.as("coupons 에 uk_coupons_user_template 가 없다. 엔티티 @Table 선언이 있는데도 없다면, 표에 같은 (user_id, template_id)"
				+ " 행이 있어 ddl-auto: update 가 UNIQUE 를 만들지 못한 것이다(기동은 멈추지 않는다). 테스트 DB 의 coupons 에서 중복"
				+ " 행을 지우고 다시 돌린다.")
			.isNotEmpty();
		assertThat(rows)
			.as("uk_coupons_user_template 의 컬럼 순서. 같은 이름이 이미 있으면 ddl-auto: update 는 다시 만들지 않으므로, 선언을"
				+ " 바꿨다면 테스트 DB 에서 그 이름을 지우고 다시 돌린다.")
			.extracting(row -> row.get("COLUMN_NAME"))
			.containsExactly("user_id", "template_id");
		assertThat(rows).as("uk_coupons_user_template 의 NON_UNIQUE")
			.extracting(row -> ((Number) row.get("NON_UNIQUE")).intValue())
			.containsOnly(UNIQUE);
	}

	@Test
	@DisplayName("같은 사용자·템플릿 쿠폰을 두 번 저장하면 두 번째가 uk_coupons_user_template 에 걸려 UNIQUE 위반으로 판별되고 행은 하나만 남는다")
	void secondCouponForSameUserAndTemplateIsRejected() {
		// given
		couponRepository.saveAndFlush(Coupon.createFromTemplate(template, userId, ISSUED_AT));

		// when
		Throwable thrown = catchThrowable(
			() -> couponRepository.saveAndFlush(Coupon.createFromTemplate(template, userId, ISSUED_AT)));

		// then
		assertThat(thrown).isInstanceOfSatisfying(DataIntegrityViolationException.class, e -> {
			assertThat(UniqueConstraintViolations.isUniqueViolation(e)).as("UNIQUE 위반으로 판별한다").isTrue();
			assertThat(e.getMostSpecificCause()).as("MySQL 이 알려 준 제약 이름")
				.hasMessageContaining("uk_coupons_user_template");
		});
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM coupons WHERE user_id = ? AND template_id = ?", Integer.class, userId,
			template.getId()))
			.isEqualTo(1);
	}

	@Test
	@DisplayName("템플릿 잠금을 쥔 채 이미 받았는지 확인하는 조회는 표 전체를 훑지 않고 uk_coupons_user_template 로 한 행을 찾는다")
	void alreadyIssuedLookupUsesUniqueKey() {
		// given: 찾는 행이 없으면 MySQL 이 "no matching row in const table" 로 끝내 key 가 비므로 찾을 행을 먼저 저장한다
		couponRepository.saveAndFlush(Coupon.createFromTemplate(template, userId, ISSUED_AT));

		// when: existsByUserIdAndTemplateId 가 만드는 조건과 같은 SQL
		Map<String, Object> plan = jdbcTemplate.queryForMap(
			"EXPLAIN SELECT id FROM coupons WHERE user_id = ? AND template_id = ? LIMIT 1", userId, template.getId());

		// then: const 는 UNIQUE 의 모든 컬럼을 값으로 찾아 많아야 한 행만 읽는다는 뜻이다
		assertThat(plan.get("key")).isEqualTo("uk_coupons_user_template");
		assertThat(plan.get("type")).as("접근 방식").isEqualTo("const");
	}
}
