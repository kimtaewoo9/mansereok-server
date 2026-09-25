package com.mansereok.server.domain.interpret.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

// 제약·인덱스 이름은 Result 와 같은 규칙으로 고정한다. 바꿀 때는 운영 DDL 과 schema.sql 을 함께 고친다.
@Entity
@Table(
	name = "compatibility_results",
	uniqueConstraints = @UniqueConstraint(name = "uk_compatibility_results_payment_id", columnNames = "payment_id"),
	indexes = @Index(name = "idx_compatibility_results_status_updated_at", columnList = "status, updated_at")
)
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompatibilityResult {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long userId;

	@Column(name = "payment_id") // 하나의 결제에 하나의 결과만 연결(uk_compatibility_results_payment_id)
	private Long paymentId;

	@Column(name = "person1_name")
	private String person1Name;

	@Column(name = "person1_ilgan")
	private String person1Ilgan;

	@Column(name = "person2_name")
	private String person2Name;

	@Column(name = "person2_ilgan")
	private String person2Ilgan;

	@Column(name = "compatibility_score")
	private Integer compatibilityScore;

	@Column(columnDefinition = "MEDIUMTEXT")
	private String interpretation;  // GPT 생성 궁합 분석. 긴 본문도 담도록 MEDIUMTEXT

	@Column(columnDefinition = "TEXT")
	private String summary;

	@Column(length = 512)
	private String ogImageUrl;

	@Column(name = "product_name")
	private String productName;

	@Enumerated(EnumType.STRING)
	private ResultStatus status = ResultStatus.INPUT_REQUIRED;

	private LocalDateTime createdAt;

	// 엔티티를 고쳐 저장할 때마다 바뀐다. JPQL 벌크 UPDATE(updateOgImageUrl 등)는 엔티티 콜백을 거치지 않아 바꾸지 않는다.
	private LocalDateTime updatedAt;

	@PrePersist
	protected void onCreate() {
		LocalDateTime now = LocalDateTime.now();
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	protected void onUpdate() {
		updatedAt = LocalDateTime.now();
	}

	public static CompatibilityResult createInitial(Long userId, Long paymentId,
		String productName) {
		CompatibilityResult result = new CompatibilityResult();
		result.userId = userId;
		result.paymentId = paymentId;
		result.productName = productName;
		result.status = ResultStatus.INPUT_REQUIRED;
		return result;
	}

	public void completeInterpretation(String interpretation, Integer score, String summary) {
		this.interpretation = interpretation;
		this.compatibilityScore = score;
		this.summary = summary;
		this.status = ResultStatus.COMPLETED;
	}

	/**
	 * 정보 입력 대기(INPUT_REQUIRED)인 결과만 해석 중(PROCESSING)으로 바꾼다.
	 *
	 * @return 바꿨으면 true. 이미 해석 중이거나 완료된 결과는 그대로 두고 false.
	 */
	public boolean markProcessing() {
		if (status != ResultStatus.INPUT_REQUIRED) {
			return false;
		}
		this.status = ResultStatus.PROCESSING;
		return true;
	}

	/**
	 * 해석 중(PROCESSING)인 결과만 정보 입력 대기(INPUT_REQUIRED)로 되돌린다. 해석이 실패했거나 시작하지 못했을 때 부른다.
	 * 환불 검사는 입력 대기인 결과만 통과시킨다. 다만 지금 환불(PaymentService.cancelPayment)은 사주 결과 표만 찾으므로,
	 * 궁합 결제는 환불이 궁합 결과 표도 찾게 된 뒤부터 이 검사를 받는다.
	 *
	 * @return 되돌렸으면 true. 입력 대기이거나 이미 완료된 결과는 그대로 두고 false.
	 */
	public boolean revertToInputRequired() {
		if (status != ResultStatus.PROCESSING) {
			return false;
		}
		this.status = ResultStatus.INPUT_REQUIRED;
		return true;
	}

	// 두 사람의 이름과 일간만 채우고 상태는 바꾸지 않는다.
	public void updatePersonsInformation(
		String person1Name,
		String person1Ilgan,
		String person2Name,
		String person2Ilgan
	) {
		this.person1Name = person1Name;
		this.person1Ilgan = person1Ilgan;
		this.person2Name = person2Name;
		this.person2Ilgan = person2Ilgan;
	}
}
