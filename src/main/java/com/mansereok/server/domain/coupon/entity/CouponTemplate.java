package com.mansereok.server.domain.coupon.entity;

import com.mansereok.server.domain.discount.entity.DiscountType;
import com.mansereok.server.global.exception.CouponSoldOutException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnDefault;

@Entity
@Table(name = "coupon_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CouponTemplate {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private String name; // 쿠폰 이름 .
	private String description; // 예: "전 상품 사용 가능!"

	@Enumerated(EnumType.STRING)
	private DiscountType discountType; // PERCENTAGE, FIXED_AMOUNT
	private int discountValue;
	private int minPurchaseAmount;

	// 발급 가능 기간 (이벤트 기간)
	private LocalDateTime issueStartDate;
	private LocalDateTime issueEndDate;

	// 유효 기간 설정 (둘 중 하나 사용)
	private Integer validDaysAfterIssue; // 발급 후 30일간 유효
	private LocalDateTime validUntil;    // 특정 날짜까지만 유효 (2024-12-31)

	// 선착순 상한. null 이면 무제한이라는 뜻이 있어 Integer 로 둔다.
	private Integer maxIssueCount;

	// 지금까지 발급한 수. "값 없음" 이 뜻하는 것이 없으므로 int 로 두고, 컬럼도 NOT NULL DEFAULT 0 이다.
	// 템플릿은 운영자가 SQL 로 넣으므로 이 칸을 비워 넣어도 DB 가 0 을 채운다. @ColumnDefault 는 ddl-auto 로 만드는 로컬 테스트
	// DB 에도 같은 DEFAULT 0 을 걸기 위해 둔다. 예전처럼 NULL 인 행이 남아 있으면 이 엔티티를 읽는 순간 실패하므로, 운영에는
	// NULL 을 0 으로 채우고 NOT NULL 로 바꾸는 DDL 을 이 코드보다 먼저 적용한다.
	@Column(nullable = false)
	@ColumnDefault("0")
	private int currentIssueCount;

	// 1인당 발급 가능 횟수 (보통 1회)
	private int maxCountPerUser;

	/**
	 * 선착순 상한만큼 모두 발급했으면 true. 상한이 없으면(null) 늘 false 다.
	 *
	 * <p>쿠폰 받기({@link #incrementIssueCount()})와 이벤트 목록의 마감 표시가 이 한 곳의 판정을 함께 쓴다.
	 */
	public boolean isSoldOut() {
		return maxIssueCount != null && currentIssueCount >= maxIssueCount;
	}

	/**
	 * 발급 수를 1 올린다. 호출자는 템플릿 행을 잠근 채 불러야 동시에 들어온 요청이 상한을 넘기지 않는다.
	 *
	 * @throws CouponSoldOutException 이미 상한만큼 발급했을 때. 발급 수는 그대로 둔다.
	 */
	public void incrementIssueCount() {
		if (isSoldOut()) {
			throw new CouponSoldOutException();
		}
		this.currentIssueCount++;
	}

}
