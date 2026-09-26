package com.mansereok.server.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.mansereok.server.domain.coupon.dto.CouponEventDto;
import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.coupon.entity.CouponTemplate;
import com.mansereok.server.domain.coupon.repository.CouponRepository;
import com.mansereok.server.domain.coupon.repository.CouponTemplateRepository;
import com.mansereok.server.domain.order.scheduler.OrderExpirationScheduler;
import com.mansereok.server.support.PaymentMySqlTest;
import com.mansereok.server.support.fixture.CouponFixture;
import com.mansereok.server.support.fixture.CouponTemplateFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 쿠폰 서비스가 "지금" 을 스프링의 Clock 빈에서 읽고, 그 시각으로 실제 MySQL 에서 내 쿠폰함과 이벤트 목록을 거르는지 확인한다.
 *
 * <p>Clock 빈을 목으로 바꿔 지금을 2031-03-14 23:59:59(서울, 자정 직전)로 고정한다. 실제 날짜와 멀리 떨어진 시각이라, 쿼리가 DB 의
 * NOW() 를 쓰거나 서비스가 시스템 시계를 읽으면 결과가 달라져 실패한다. 만료 시각이 없는(NULL) 쿠폰이 쿠폰함에 나오는지는 쿼리의
 * NULL 처리라 DB 로만 볼 수 있다.
 *
 * <p>주문 만료 스케줄러도 목으로 바꾼다. 이 클래스는 목 조합이 달라 스프링 컨텍스트를 새로 띄우는데, 그때 스케줄러가 다른 스레드에서
 * 곧바로 한 번 돌며 같은 Clock 을 읽는다. Mockito 의 스텁 설정은 다른 스레드의 호출과 겹치면 그 호출에 붙을 수 있어, 테스트가 Clock
 * 을 스텁하는 순간과 겹치면 고정한 시각이 적용되지 않는다(스케줄러를 그대로 둔 채 전체를 돌렸을 때 발급 기간 확인이 실제 시각으로
 * 돌아 한 번 실패했다). 스텁 전에 돌면 null 시각을 읽어 오류 로그를 남기고, 스텁 뒤에 돌면 2031년 기준으로 다른 테스트의 결제 대기
 * 주문을 만료시킬 수도 있다.
 *
 * <p>쿠폰은 사용자 id 만 들고 users 표를 참조하지 않으므로 실행 키에서 만든 사용자 id 를 그대로 쓰고, 뒤 정리에서 그 사용자 id 와
 * 이번에 만든 템플릿 id 로만 지운다.
 */
class CouponClockMySqlTest extends PaymentMySqlTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	// 2031-03-14 23:59:59 (서울)
	private static final Instant FIXED_INSTANT = Instant.parse("2031-03-14T14:59:59Z");
	private static final LocalDateTime NOW = LocalDateTime.of(2031, 3, 14, 23, 59, 59);

	@MockitoBean
	private Clock clock;
	@MockitoBean
	private OrderExpirationScheduler orderExpirationScheduler;

	@Autowired
	private CouponService couponService;
	@Autowired
	private CouponRepository couponRepository;
	@Autowired
	private CouponTemplateRepository couponTemplateRepository;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	// 쿠폰은 사용자 id 만 들고 users 표를 참조하지 않으므로, 실행 키에서 만든 id 를 그대로 쓴다.
	private final Long userId = Long.parseLong(runId, 16);
	private final List<Long> createdTemplateIds = new ArrayList<>();

	@BeforeEach
	void fixClock() {
		given(clock.instant()).willReturn(FIXED_INSTANT);
		given(clock.getZone()).willReturn(SEOUL);
	}

	@AfterEach
	void deleteRowsOfThisRun() {
		jdbcTemplate.update("DELETE FROM coupons WHERE user_id = ?", userId);
		for (Long templateId : createdTemplateIds) {
			jdbcTemplate.update("DELETE FROM coupon_templates WHERE id = ?", templateId);
		}
	}

	@Test
	@DisplayName("내 쿠폰함은 Clock 의 지금 기준으로 만료 시각이 없는 쿠폰과 만료 시각 그 순간인 쿠폰을 보여 주고, 1초 지난 쿠폰과 쓴 쿠폰은 뺀다")
	void myCouponsFollowClockAndIncludeCouponsWithoutExpiry() {
		// given
		saveCoupon(CouponFixture.usableCoupon().name("기간 없음").expiresAt(null));
		saveCoupon(CouponFixture.usableCoupon().name("지금 만료").expiresAt(NOW));
		saveCoupon(CouponFixture.usableCoupon().name("1초 전 만료").expiresAt(NOW.minusSeconds(1)));
		saveCoupon(CouponFixture.usableCoupon().name("기간 없음, 사용함").expiresAt(null).usedAt(NOW.minusDays(1)));

		// when
		List<Coupon> myCoupons = couponService.getMyCoupons(userId);

		// then
		assertThat(myCoupons).extracting(Coupon::getName).containsExactlyInAnyOrder("기간 없음", "지금 만료");
	}

	@Test
	@DisplayName("자정 직전에 받는 '발급 후 1일' 쿠폰은 이벤트 목록에 Clock 날짜의 다음 날까지로 뜨고, 받으면 그 시각에 만료되는 쿠폰이 생긴다")
	void eventListAndIssuedCouponUseClockDate() {
		// given: Clock 의 지금에만 발급 기간인 이벤트. DB 의 NOW() 나 시스템 시계로 거르면 목록에 나오지 않는다
		CouponTemplate template = saveTemplate(CouponTemplateFixture.issuableNow().withoutId()
			.name("자정 직전 쿠폰 " + runId).issuePeriod(NOW.minusDays(1), NOW.plusSeconds(1))
			.validDaysAfterIssue(1));

		// when
		List<CouponEventDto> eventsBeforeDownload = couponService.getCouponEvents(userId);
		couponService.downloadCoupon(userId, template.getId());
		List<CouponEventDto> eventsAfterDownload = couponService.getCouponEvents(userId);

		// then
		assertThat(eventsBeforeDownload).filteredOn(event -> template.getId().equals(event.getTemplateId()))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.getValidPeriod()).isEqualTo("2031.03.15 까지");
				assertThat(event.isIssued()).isFalse();
			});
		assertThat(eventsAfterDownload).filteredOn(event -> template.getId().equals(event.getTemplateId()))
			.singleElement()
			.satisfies(event -> assertThat(event.isIssued()).isTrue());
		assertThat(jdbcTemplate.queryForObject(
			"SELECT expires_at FROM coupons WHERE user_id = ? AND template_id = ?", LocalDateTime.class, userId,
			template.getId()))
			.isEqualTo(LocalDateTime.of(2031, 3, 15, 23, 59, 59));
	}

	private void saveCoupon(CouponFixture coupon) {
		couponRepository.saveAndFlush(coupon.withoutId().userId(userId).build());
	}

	private CouponTemplate saveTemplate(CouponTemplateFixture template) {
		CouponTemplate saved = couponTemplateRepository.saveAndFlush(template.build());
		createdTemplateIds.add(saved.getId());
		return saved;
	}
}
