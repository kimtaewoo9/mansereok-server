package com.mansereok.server.domain.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.coupon.dto.CouponEventDto;
import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.coupon.entity.CouponTemplate;
import com.mansereok.server.domain.coupon.repository.CouponRepository;
import com.mansereok.server.domain.coupon.repository.CouponTemplateRepository;
import com.mansereok.server.domain.discount.entity.DiscountType;
import java.lang.reflect.Constructor;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest
class CouponServiceIntegrationTest {

	@Autowired
	private CouponService couponService;

	@Autowired
	private CouponTemplateRepository couponTemplateRepository;

	@Autowired
	private CouponRepository couponRepository;

	@AfterEach
	void tearDown() {
		couponRepository.deleteAll();
		couponTemplateRepository.deleteAll();
	}

	@Test
	@DisplayName("전체 쿠폰 목록 조회 시 DTO 변환(날짜, 할인타입)과 발급 여부가 정확해야 한다")
	void getCouponEventsTest() throws Exception {
		// given
		// 1. 템플릿 2개 생성 (하나는 기간제, 하나는 날짜지정)
		CouponTemplate t1 = createTestTemplate("신규회원 쿠폰", DiscountType.PERCENTAGE, 10, 30, null);
		CouponTemplate t2 = createTestTemplate("마감임박 쿠폰", DiscountType.FIXED_AMOUNT, 1000, null,
			LocalDateTime.of(2030, 12, 31, 23, 59));

		couponTemplateRepository.saveAll(List.of(t1, t2));
		Long userId = 1L;

		// when
		List<CouponEventDto> events = couponService.getCouponEvents(userId);

		// then
		assertThat(events).hasSize(2);

		// 첫 번째 쿠폰 검증 (PERCENTAGE, 발급 후 30일 계산)
		CouponEventDto dto1 = events.stream().filter(e -> e.getName().equals("신규회원 쿠폰")).findFirst()
			.get();
		assertThat(dto1.getDiscountType()).isEqualTo("PERCENTAGE");
		assertThat(dto1.isIssued()).isFalse();
		// 날짜 포맷 확인 (오늘 날짜 + 30일)
		String expectedDate =
			LocalDateTime.now().plusDays(30).toLocalDate().toString().replace("-", ".") + " 까지";
		assertThat(dto1.getValidPeriod()).isEqualTo(expectedDate);

		// 두 번째 쿠폰 검증 (FIXED_AMOUNT, 고정 날짜)
		CouponEventDto dto2 = events.stream().filter(e -> e.getName().equals("마감임박 쿠폰")).findFirst()
			.get();
		assertThat(dto2.getDiscountType()).isEqualTo("FIXED_AMOUNT");
		assertThat(dto2.getValidPeriod()).isEqualTo("2030.12.31 까지");
	}

	@Test
	@DisplayName("쿠폰을 다운로드하면 내 쿠폰함에 보이고, 목록에서는 '받음(isIssued=true)' 상태가 되어야 한다")
	void downloadAndMyCouponTest() throws Exception {
		// given
		CouponTemplate template = createTestTemplate("다운로드 테스트 쿠폰", DiscountType.FIXED_AMOUNT, 5000,
			7, null);
		couponTemplateRepository.save(template);
		Long userId = 100L;

		// when : 쿠폰 다운로드 실행
		couponService.downloadCoupon(userId, template.getId());

		// then 1: 내 쿠폰함 조회 (getMyCoupons)
		List<Coupon> myCoupons = couponService.getMyCoupons(userId);
		assertThat(myCoupons).hasSize(1);
		assertThat(myCoupons.get(0).getName()).isEqualTo("다운로드 테스트 쿠폰");
		assertThat(myCoupons.get(0).getDiscountValue()).isEqualTo(5000);

		// then 2: 전체 목록 재조회 시 isIssued가 true여야 함
		List<CouponEventDto> events = couponService.getCouponEvents(userId);
		assertThat(events.get(0).isIssued()).isTrue();
	}

	// [헬퍼 메서드] 리플렉션으로 템플릿 생성 (엔티티 코드 수정 불필요)
	private CouponTemplate createTestTemplate(String name, DiscountType type, int value,
		Integer validDays, LocalDateTime validUntil) throws Exception {
		Constructor<CouponTemplate> constructor = CouponTemplate.class.getDeclaredConstructor();
		constructor.setAccessible(true);
		CouponTemplate t = constructor.newInstance();

		ReflectionTestUtils.setField(t, "name", name);
		ReflectionTestUtils.setField(t, "discountType", type);
		ReflectionTestUtils.setField(t, "discountValue", value);
		ReflectionTestUtils.setField(t, "minPurchaseAmount", 0);
		ReflectionTestUtils.setField(t, "issueStartDate", LocalDateTime.now().minusDays(1)); // 어제부터
		ReflectionTestUtils.setField(t, "issueEndDate",
			LocalDateTime.now().plusDays(10));   // 10일 뒤까지
		ReflectionTestUtils.setField(t, "validDaysAfterIssue", validDays);
		ReflectionTestUtils.setField(t, "validUntil", validUntil);
		ReflectionTestUtils.setField(t, "maxCountPerUser", 1);

		return t;
	}
}
