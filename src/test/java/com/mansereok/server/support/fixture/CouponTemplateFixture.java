package com.mansereok.server.support.fixture;

import com.mansereok.server.domain.coupon.entity.CouponTemplate;
import com.mansereok.server.domain.discount.entity.DiscountType;
import java.time.LocalDateTime;
import org.springframework.beans.BeanUtils;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 테스트용 CouponTemplate 을 만든다. CouponTemplate 은 생성자가 protected 이고 만드는 메서드가 없어(운영자가 SQL 로 넣는다)
 * 리플렉션이 필요한데, 그 우회를 이 클래스 한 곳에만 둔다.
 *
 * <p>기본값은 "지금 받을 수 있는 1인 1장 정액 1,000원 쿠폰 이벤트" 다. 발급 기간은 어제부터 10일 뒤까지, 받은 날부터 30일 유효,
 * 선착순 상한 없음, 발급 수 0. 테스트는 결과에 영향을 주는 값만 바꾸고 나머지는 기본값을 쓴다. 호출할 때마다 새 빌더를 돌려주므로
 * 테스트끼리 값이 섞이지 않는다.
 */
public final class CouponTemplateFixture {

	private Long id = 1L;
	private String name = "테스트 쿠폰 이벤트";
	private DiscountType discountType = DiscountType.FIXED_AMOUNT;
	private int discountValue = 1000;
	private int minPurchaseAmount = 0;
	private LocalDateTime issueStartDate = LocalDateTime.now().minusDays(1);
	private LocalDateTime issueEndDate = LocalDateTime.now().plusDays(10);
	private Integer validDaysAfterIssue = 30;
	private Integer maxIssueCount = null;
	private int currentIssueCount = 0;
	private int maxCountPerUser = 1;

	private CouponTemplateFixture() {
	}

	/** 지금 받을 수 있고 선착순 상한이 없는 쿠폰 이벤트. */
	public static CouponTemplateFixture issuableNow() {
		return new CouponTemplateFixture();
	}

	public CouponTemplateFixture id(Long id) {
		this.id = id;
		return this;
	}

	/** DB 에 저장할 때는 id 를 비워 둔다(IDENTITY 가 채운다). */
	public CouponTemplateFixture withoutId() {
		this.id = null;
		return this;
	}

	public CouponTemplateFixture name(String name) {
		this.name = name;
		return this;
	}

	/** 쿠폰을 받을 수 있는 기간(이벤트 기간). 시작·끝 시각도 기간에 든다. */
	public CouponTemplateFixture issuePeriod(LocalDateTime issueStartDate, LocalDateTime issueEndDate) {
		this.issueStartDate = issueStartDate;
		this.issueEndDate = issueEndDate;
		return this;
	}

	/** 선착순 상한. null 이면 무제한이다. */
	public CouponTemplateFixture maxIssueCount(Integer maxIssueCount) {
		this.maxIssueCount = maxIssueCount;
		return this;
	}

	public CouponTemplateFixture currentIssueCount(int currentIssueCount) {
		this.currentIssueCount = currentIssueCount;
		return this;
	}

	public CouponTemplate build() {
		CouponTemplate template = BeanUtils.instantiateClass(CouponTemplate.class);
		ReflectionTestUtils.setField(template, "id", id);
		ReflectionTestUtils.setField(template, "name", name);
		ReflectionTestUtils.setField(template, "discountType", discountType);
		ReflectionTestUtils.setField(template, "discountValue", discountValue);
		ReflectionTestUtils.setField(template, "minPurchaseAmount", minPurchaseAmount);
		ReflectionTestUtils.setField(template, "issueStartDate", issueStartDate);
		ReflectionTestUtils.setField(template, "issueEndDate", issueEndDate);
		ReflectionTestUtils.setField(template, "validDaysAfterIssue", validDaysAfterIssue);
		ReflectionTestUtils.setField(template, "maxIssueCount", maxIssueCount);
		ReflectionTestUtils.setField(template, "currentIssueCount", currentIssueCount);
		ReflectionTestUtils.setField(template, "maxCountPerUser", maxCountPerUser);
		return template;
	}
}
