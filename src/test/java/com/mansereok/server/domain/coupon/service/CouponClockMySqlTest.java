package com.mansereok.server.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 쿠폰 서비스가 "지금" 을 스프링의 Clock 빈에서 읽고, 그 시각으로 실제 MySQL 에서 내 쿠폰함과 이벤트 목록을 거르는지 확인한다.
 *
 * <p>Clock 빈을 2031-03-14 23:59:59(서울, 자정 직전)에 멈춘 Clock.fixed 로 바꾼다. 실제 날짜와 멀리 떨어진 시각이라, 쿼리가 DB 의
 * NOW() 를 쓰거나 서비스가 시스템 시계를 읽으면 결과가 달라져 실패한다. 만료 시각이 없는(NULL) 쿠폰이 쿠폰함에 나오는지는 쿼리의
 * NULL 처리라 DB 로만 볼 수 있다.
 *
 * <p>주문 만료 스케줄러는 목으로 바꾼다. 스케줄러는 컨텍스트가 뜰 때 한 번, 그 뒤 30분마다 같은 Clock 으로 만료 기준 시각을 정한다.
 * 2031년 시계로 돌면 같은 DB 에 있는 결제 대기 주문을 모두 만료시킨다.
 *
 * <p>쿠폰은 사용자 id 만 들고 users 표를 참조하지 않으므로 실행 키에서 만든 사용자 id 를 그대로 쓰고, 뒤 정리에서 그 사용자 id 와
 * 이번에 만든 템플릿 id 로만 지운다.
 */
class CouponClockMySqlTest extends PaymentMySqlTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	// 2031-03-14 23:59:59 (서울)
	private static final Instant FIXED_INSTANT = Instant.parse("2031-03-14T14:59:59Z");
	private static final LocalDateTime NOW = LocalDateTime.of(2031, 3, 14, 23, 59, 59);

	// 스프링의 Clock 빈을 fixedClock() 이 만든 고정 시계로 바꾼다.
	@TestBean(methodName = "fixedClock")
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

	static Clock fixedClock() {
		return Clock.fixed(FIXED_INSTANT, SEOUL);
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
	@DisplayName("자정 직전에 보는 이벤트 목록은 Clock 의 지금에만 발급 기간인 '발급 후 1일' 이벤트를 보여 주고, 유효 기간을 Clock 날짜의 다음 날까지로 적는다")
	void eventListShowsValidPeriodFromClockDate() {
		// given: Clock 의 지금에만 발급 기간인 이벤트. DB 의 NOW() 나 시스템 시계로 거르면 목록에 나오지 않는다
		CouponTemplate template = saveTemplate(oneDayCouponIssuableOnlyAroundNow());

		// when
		List<CouponEventDto> events = couponService.getCouponEvents(userId);

		// then
		assertThat(events).filteredOn(event -> template.getId().equals(event.getTemplateId()))
			.singleElement()
			.satisfies(event -> {
				assertThat(event.getValidPeriod()).isEqualTo("2031.03.15 까지");
				assertThat(event.isIssued()).isFalse();
			});
	}

	@Test
	@DisplayName("자정 직전에 '발급 후 1일' 쿠폰을 받으면 받은 시각부터 1일 뒤(2031-03-15 23:59:59)에 만료되는 쿠폰이 저장된다")
	void downloadedCouponExpiresOneDayAfterClockTime() {
		// given
		CouponTemplate template = saveTemplate(oneDayCouponIssuableOnlyAroundNow());

		// when
		couponService.downloadCoupon(userId, template.getId());

		// then
		assertThat(jdbcTemplate.queryForObject(
			"SELECT expires_at FROM coupons WHERE user_id = ? AND template_id = ?", LocalDateTime.class, userId,
			template.getId()))
			.isEqualTo(LocalDateTime.of(2031, 3, 15, 23, 59, 59));
	}

	@Test
	@DisplayName("이미 받은 이벤트는 Clock 의 지금 기준 이벤트 목록에 받음으로 표시된다")
	void eventListMarksDownloadedEventAsIssued() {
		// given
		CouponTemplate template = saveTemplate(oneDayCouponIssuableOnlyAroundNow());
		couponService.downloadCoupon(userId, template.getId());

		// when
		List<CouponEventDto> events = couponService.getCouponEvents(userId);

		// then
		assertThat(events).filteredOn(event -> template.getId().equals(event.getTemplateId()))
			.singleElement()
			.satisfies(event -> assertThat(event.isIssued()).isTrue());
	}

	/** Clock 의 지금 앞뒤로만 발급 기간인 '발급 후 1일' 쿠폰 이벤트. */
	private CouponTemplateFixture oneDayCouponIssuableOnlyAroundNow() {
		return CouponTemplateFixture.issuableNow().withoutId()
			.name("자정 직전 쿠폰 " + runId).issuePeriod(NOW.minusDays(1), NOW.plusSeconds(1))
			.validDaysAfterIssue(1);
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
