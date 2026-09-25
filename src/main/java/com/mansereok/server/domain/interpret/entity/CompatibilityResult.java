package com.mansereok.server.domain.interpret.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "compatibility_results")
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompatibilityResult {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	private Long userId;

	@Column(name = "payment_id", unique = true)
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

	@Column(columnDefinition = "TEXT")
	private String interpretation;  // GPT 생성 궁합 분석

	@Column(columnDefinition = "TEXT")
	private String summary;

	@Column(length = 512)
	private String ogImageUrl;

	@Column(name = "product_name")
	private String productName;

	@Enumerated(EnumType.STRING)
	private ResultStatus status = ResultStatus.INPUT_REQUIRED;

	private LocalDateTime createdAt;

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
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
	 * 입력 대기로 돌아가야 사용자가 다시 요청하거나 환불받을 수 있다.
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

	// 두 사람의 이름과 일간만 채운다. 상태는 markProcessing 과 revertToInputRequired 로만 바꾼다.
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
