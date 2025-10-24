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

	private Long userId;  // 요청한 사용자

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

	@Column(name = "product_name")
	private String productName;

	@Enumerated(EnumType.STRING)
	private ProductStatus status = ProductStatus.PENDING;

	private LocalDateTime createdAt;

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
	}

	public static CompatibilityResult createPending(
		Long userId, String person1Name, String person1Ilgan, String person2Name,
		String person2Ilgan, String productName) {
		CompatibilityResult result = new CompatibilityResult();
		result.userId = userId;
		result.person1Name = person1Name;
		result.person1Ilgan = person1Ilgan;
		result.person2Name = person2Name;
		result.person2Ilgan = person2Ilgan;
		result.productName = productName;
		result.status = ProductStatus.PENDING;
		return result;
	}

	public static CompatibilityResult create(
		Long userId, String person1Name, String person1Ilgan, String person2Name,
		String person2Ilgan, Integer compatibilityScore, String interpretation,
		String productName) {
		CompatibilityResult result = new CompatibilityResult();
		result.userId = userId;
		result.person1Name = person1Name;
		result.person1Ilgan = person1Ilgan;
		result.person2Name = person2Name;
		result.person2Ilgan = person2Ilgan;
		result.compatibilityScore = compatibilityScore;
		result.interpretation = interpretation;
		result.productName = productName;
		return result;
	}
}
