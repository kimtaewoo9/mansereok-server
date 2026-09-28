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
	indexes = {
		// 오래 해석 중(PROCESSING)에 머문 결과를 되돌릴 때 상태와 마지막 변경 시각으로 찾는다.
		@Index(name = "idx_results_status_updated_at", columnList = "status, updated_at"),
		// 탈퇴 벌크 DELETE(deleteAllByUserId)가 그 사용자의 행만 잠그게 한다. 없으면 REPEATABLE READ 에서 표 전체를 훑으며
		// 모든 행과 표 끝을 잠가, 탈퇴 트랜잭션이 끝날 때까지 다른 사용자의 결과 저장이 멈춘다. 결과 목록 조회도 이 인덱스를 쓴다.
		// user_id 로 시작하는 복합 인덱스(예: (user_id, created_at))가 들어오면 이 인덱스는 지운다.
		@Index(name = "idx_results_user_id", columnList = "user_id")
	}
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

	/**
	 * 고친 시각을 지금으로 바꾼다. 다만 해석 중(PROCESSING)으로 저장할 때는 바꾸지 않는다.
	 *
	 * <p>해석 중인 결과의 updated_at 은 해석을 시작한 시각(ResultRepository.markProcessingIfInputRequired 가 넣은 값)이다. 해석
	 * 실행은 결과를 쓸 때 이 값으로 자기가 시작한 해석인지 가린다({@link #isProcessingStartedAt}). 입력 정보를 채우는 저장이 이 값을
	 * 바꾸면 같은 실행의 결과 저장이 자기를 다른 실행으로 보고 멈춘다. 완료나 되돌리기는 저장할 때 상태가 이미 해석 중이 아니므로 지금
	 * 시각으로 바뀐다.
	 */
	@PreUpdate
	protected void onUpdate() {
		if (status == ResultStatus.PROCESSING) {
			return;
		}
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

	/**
	 * 해석 중이고, 그 해석을 startedAt 에 시작했는지 본다.
	 *
	 * <p>해석 실행은 결과를 쓰기 전에 자기가 해석을 시작한 시각(ResultService.startProcessing 이 돌려준 값)으로 이 메서드를 부른다.
	 * 오래 멈춰 정보 입력 대기로 되돌려졌으면 해석 중이 아니고, 그 뒤 같은 결제로 해석을 다시 시작했으면 시작 시각이 달라 false 다.
	 * 시작 시각은 초 단위다. 되돌리기는 해석을 시작하고 stale-after(기본 60분)가 지나야 일어나므로, 아직 도는 해석과 다시 시작한
	 * 해석이 같은 초에 시작해 서로를 가리지 못하는 일은 없다.
	 */
	public boolean isProcessingStartedAt(LocalDateTime startedAt) {
		return status == ResultStatus.PROCESSING && updatedAt != null && updatedAt.equals(startedAt);
	}

	/**
	 * 해석을 시작하며 입력 정보를 채운다. 상태와 해석을 시작한 시각(updated_at)은 바꾸지 않는다.
	 *
	 * <p>해석 중(PROCESSING)으로 바꾸는 일은 ResultRepository.markProcessingIfInputRequired 의 조건부 UPDATE 가 맡고, 이 메서드는
	 * 그 관문을 지난 결과에만 쓴다. 자기가 시작한 해석인지는 부르는 쪽이 {@link #isProcessingStartedAt} 으로 먼저 가린다.
	 *
	 * @throws IllegalStateException 해석 중이 아닐 때. 완료된 결과에 다른 사람의 정보를 덮어써 본문과 인적 정보가 어긋나지 않게 한다.
	 */
	public void updateInformation(String name, LocalDate solarDate, LocalTime solarTime,
		String gender, Boolean isLunar, String ilgan) {
		requireProcessing("입력 정보를 채울");
		this.name = name;
		this.solarDate = solarDate;
		this.solarTime = solarTime;
		this.gender = gender;
		this.isLunar = isLunar;
		this.ilgan = ilgan;
	}

	/**
	 * 해석 본문과 요약을 넣고 완료(COMPLETED)로 바꾼다. 자기가 시작한 해석인지는 부르는 쪽이 {@link #isProcessingStartedAt} 으로
	 * 먼저 가린다. 그래서 되돌린 뒤 다시 시작한 결과에 먼저 시작한 해석의 본문이 붙지 않는다.
	 *
	 * @throws IllegalStateException 해석 중(PROCESSING)이 아닐 때. 이미 완료된 결과를 다시 덮어쓰는 저장을 막는다.
	 */
	public void completeInterpretation(String interpretation, String summary) {
		requireProcessing("해석 결과를 저장할");
		this.interpretation = interpretation;
		this.summary = summary;
		this.status = ResultStatus.COMPLETED;
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

	private void requireProcessing(String action) {
		if (status != ResultStatus.PROCESSING) {
			throw new IllegalStateException(String.format(
				"해석 중(PROCESSING)인 결과에만 %s 수 있다. paymentId=%s, 지금 상태=%s", action, paymentId, status));
		}
	}
}
