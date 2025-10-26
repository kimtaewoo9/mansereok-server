package com.mansereok.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Entity
@Table(name = "results")
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Result {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id")
	private Long userId;  // User 엔티티 대신 ID만 저장

	@Column(name = "payment_id", unique = true) // unique 제약 .. 하나의 결제에 하나의 결과만 연결
	private Long paymentId;

	@Column(nullable = false, length = 100)
	private String name;

	// 생년월일시를 저장 해야되나 ?
	@Column(name = "solar_date", nullable = false)
	private LocalDate solarDate;

	@Column(name = "solar_time", nullable = false)
	private LocalTime solarTime;

	@Column(nullable = false) // MALE OR FEMALE
	private String gender;

	@Column(name = "is_lunar", nullable = false)
	private Boolean isLunar;

	@Column(nullable = false, length = 10)
	private String ilgan;

	@Column(columnDefinition = "TEXT")
	private String interpretation;

	private String productName;

	@Enumerated(EnumType.STRING)
	private ResultStatus status = ResultStatus.INPUT_REQUIRED;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
		updatedAt = LocalDateTime.now();
	}

	@PreUpdate
	protected void onUpdate() {
		updatedAt = LocalDateTime.now();
	}

	public static Result createInitial(Long userId, Long paymentId, String productName) {
		Result result = new Result();
		result.userId = userId;
		result.paymentId = paymentId;
		result.productName = productName;
		result.status = ResultStatus.INPUT_REQUIRED;
		return result;
	}

	// 정보 입력시 ..
	public void updateInformation(String name, LocalDate solarDate, LocalTime solarTime,
		String gender, Boolean isLunar, String ilgan) {
		this.name = name;
		this.solarDate = solarDate;
		this.solarTime = solarTime;
		this.gender = gender;
		this.isLunar = isLunar;
		this.ilgan = ilgan;
		this.status = ResultStatus.PROCESSING;
	}

	// 해석 완료 후 COMPLETED 상태로 바꿈 .
	public void completeInterpretation(String interpretation) {
		this.interpretation = interpretation;
		this.status = ResultStatus.COMPLETED;
	}
}
