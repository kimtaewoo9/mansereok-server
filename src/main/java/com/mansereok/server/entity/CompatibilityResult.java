package com.mansereok.server.entity;

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

	public void setStatus(ResultStatus status) {
		this.status = ResultStatus.PROCESSING;
	}

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
		this.status = ResultStatus.PROCESSING;
	}
}
