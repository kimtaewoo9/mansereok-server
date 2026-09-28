package com.mansereok.server.domain.coupon.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.coupon.controller.CouponController;
import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.coupon.repository.CouponRepository;
import com.mansereok.server.domain.coupon.repository.CouponTemplateRepository;
import com.mansereok.server.domain.coupon.service.CouponService;
import com.mansereok.server.domain.discount.entity.DiscountType;
import com.mansereok.server.domain.payment.service.PaymentUserLookup;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.support.fixture.CouponFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 내 쿠폰함 응답(GET /api/coupons/my)의 JSON 키가 예전에 Coupon 엔티티를 그대로 내보내던 때와 같은지 고정한다.
 *
 * <p>{@link #COUPON_JSON_KEYS} 는 Coupon 엔티티를 이 프로젝트의 Jackson 설정으로 직렬화해 얻은 키 목록이다. 사용 여부는 Lombok
 * getter 이름(isUsed()) 때문에 used 로 나갔다. 프론트는 이 키로 읽으므로 키가 하나라도 바뀌면 이 테스트가 실패한다.
 */
@ExtendWith(MockitoExtension.class)
class CouponResponseTest {

	private static final String[] COUPON_JSON_KEYS = {"id", "userId", "name", "discountType", "discountValue",
		"minPurchaseAmount", "expiresAt", "used", "usedAt", "templateId"};

	private static final String USERNAME = "buyer";
	private static final Long USER_ID = 3L;
	// 2026-09-26 12:00 (서울)
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-26T03:00:00Z"),
		ZoneId.of("Asia/Seoul"));
	private static final LocalDateTime NOW = LocalDateTime.now(FIXED_CLOCK);

	private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();

	@Mock
	private CouponRepository couponRepository;
	@Mock
	private CouponTemplateRepository couponTemplateRepository;
	@Mock
	private UserRepository userRepository;

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	@DisplayName("from 은 쿠폰의 모든 필드를 같은 이름으로 옮긴다(사용한 쿠폰이면 used 와 usedAt 도)")
	void fromCopiesAllFields() {
		// given: 필드마다 기본값과 다른 값을 넣어, 한 필드라도 옮기지 않거나 다른 필드에서 옮기면 실패하게 한다
		LocalDateTime expiresAt = LocalDateTime.of(2026, 10, 26, 12, 0);
		LocalDateTime usedAt = LocalDateTime.of(2026, 9, 25, 18, 30);
		Coupon coupon = CouponFixture.usableCoupon().id(7L).userId(USER_ID).name("신규가입 쿠폰")
			.discountType(DiscountType.PERCENTAGE).discountValue(20).minPurchaseAmount(5000).expiresAt(expiresAt)
			.usedAt(usedAt).templateId(11L).build();

		// when
		CouponResponse response = CouponResponse.from(coupon);

		// then
		assertThat(response).isEqualTo(new CouponResponse(7L, USER_ID, "신규가입 쿠폰", DiscountType.PERCENTAGE, 20,
			5000, expiresAt, true, usedAt, 11L));
	}

	@Test
	@DisplayName("JSON 키는 예전 Coupon 엔티티 응답의 키와 같다(사용 여부는 used)")
	void jsonKeysMatchFormerEntityResponse() {
		// given
		Coupon coupon = CouponFixture.usableCoupon().build();

		// when
		JsonNode json = objectMapper.valueToTree(CouponResponse.from(coupon));

		// then
		assertThat(fieldNames(json)).containsExactlyInAnyOrder(COUPON_JSON_KEYS);
	}

	@Test
	@DisplayName("GET /api/coupons/my 는 쿠폰마다 예전과 같은 키로 답하고, 기간 없는 쿠폰은 expiresAt 을 null 로 보낸다")
	void myCouponsEndpointKeepsJsonKeys() throws Exception {
		// given
		given(userRepository.findByUsername(USERNAME)).willReturn(Optional.of(user()));
		Coupon couponWithoutExpiry = CouponFixture.usableCoupon().id(7L).userId(USER_ID).expiresAt(null).build();
		given(couponRepository.findAllAvailableByUserId(USER_ID, NOW)).willReturn(List.of(couponWithoutExpiry));

		// when
		MvcResult result = myCouponsEndpoint().perform(get("/api/coupons/my"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(7))
			.andExpect(jsonPath("$[0].used").value(false))
			.andExpect(jsonPath("$[0].expiresAt").value(nullValue()))
			.andReturn();

		// then
		JsonNode firstCoupon = objectMapper.readTree(result.getResponse().getContentAsString()).get(0);
		assertThat(fieldNames(firstCoupon)).containsExactlyInAnyOrder(COUPON_JSON_KEYS);
	}

	/** 운영과 같은 컨트롤러·서비스에 리포지토리만 목으로 바꾸고, 로그인 필터가 하던 요청자 이름 넣기는 테스트가 대신한다. */
	private MockMvc myCouponsEndpoint() {
		CouponService couponService = new CouponService(couponRepository, couponTemplateRepository, FIXED_CLOCK);
		SecurityContextHolder.getContext().setAuthentication(
			new UsernamePasswordAuthenticationToken(USERNAME, null, List.of()));
		return MockMvcBuilders.standaloneSetup(
				new CouponController(couponService, new PaymentUserLookup(userRepository)))
			.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
			.build();
	}

	private static List<String> fieldNames(JsonNode json) {
		List<String> names = new ArrayList<>();
		json.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private static User user() {
		User user = User.create(USERNAME, "구매자", "password", "buyer@example.com", LocalDate.of(1990, 1, 1),
			Gender.FEMALE, true, true, false);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}
}
