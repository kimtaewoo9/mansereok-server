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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

// 제약·인덱스 이름을 고정해 엔티티, schema.sql, 운영 DB 가 같은 이름을 쓰게 한다. 운영은 ddl-auto: validate 라 이름과 인덱스를
// 검사하지 않으므로, 바꿀 때는 운영 DDL 과 schema.sql 을 함께 고친다.
@Entity
@Table(
	name = "results",
	// 결제 하나에 결과 하나. 결제 ID 로 찾을 때 이 인덱스로 한 행만 본다.
	uniqueConstraints = @UniqueConstraint(name = "uk_results_payment_id", columnNames = "payment_id"),
	// 오래 해석 중(PROCESSING)에 머문 결과를 되돌릴 때 상태와 마지막 변경 시각으로 찾는다.
	indexes = @Index(name = "idx_results_status_updated_at", columnList = "status, updated_at")
)
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Result {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id")
	private Long userId;  // User 엔티티 대신 ID만 저장

	@Column(name = "payment_id") // 하나의 결제에 하나의 결과만 연결(uk_results_payment_id)
	private Long paymentId;

	private String name;
	// 생년월일시를 저장 해야되나 ?
	private LocalDate solarDate;

	private LocalTime solarTime;

	private String gender;

	private Boolean isLunar;

	private String ilgan;

	// 한글 한 글자가 3바이트라 TEXT(64KB)로는 약 21,800자에서 잘린다. 긴 해석문도 담도록 MEDIUMTEXT(16MB)로 둔다.
	@Column(columnDefinition = "MEDIUMTEXT")
	private String interpretation;

	@Column(columnDefinition = "TEXT")
	private String summary;

	@Column(length = 512)
	private String ogImageUrl;

	private String productName;

	@Enumerated(EnumType.STRING)
	private ResultStatus status = ResultStatus.INPUT_REQUIRED;

	private LocalDateTime createdAt;

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
	}

	// 해석 완료 후 COMPLETED 상태로 바꿈 .
	public void completeInterpretation(String interpretation, String summary) {
		this.interpretation = interpretation;
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
	 * 환불 검사는 입력 대기인 결과만 통과시키므로, 되돌려야 해석을 받지 못한 결제를 환불할 수 있다.
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
}
