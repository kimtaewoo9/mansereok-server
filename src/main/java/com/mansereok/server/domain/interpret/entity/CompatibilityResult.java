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
	indexes = {
		@Index(name = "idx_compatibility_results_status_updated_at", columnList = "status, updated_at"),
		// 탈퇴 벌크 DELETE 가 그 사용자의 행만 잠그게 한다(Result 의 idx_results_user_id 와 같은 이유).
		@Index(name = "idx_compatibility_results_user_id", columnList = "user_id")
	}
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

	// 엔티티를 고쳐 저장할 때마다 바뀐다. 해석 중으로 저장할 때는 바꾸지 않는다(onUpdate). JPQL 벌크 UPDATE(updateOgImageUrl 등)는
	// 엔티티 콜백을 거치지 않아 바꾸지 않는다.
	// 운영에서 컬럼을 더하기 전의 행과, created_at 으로 채운 뒤 이 코드가 배포되기 전까지 옛 코드가 쓴 행은 NULL 일 수 있다.
	// 그래서 배포 뒤에 같은 채우기 UPDATE(WHERE updated_at IS NULL)를 한 번 더 돌린다.
	private LocalDateTime updatedAt;

	@PrePersist
	protected void onCreate() {
		LocalDateTime now = LocalDateTime.now();
		createdAt = now;
		updatedAt = now;
	}

	/**
	 * 고친 시각을 지금으로 바꾼다. 다만 해석 중(PROCESSING)으로 저장할 때는 바꾸지 않는다. 해석 중인 결과의 updated_at 은 해석을
	 * 시작한 시각이고, 해석 실행이 자기가 시작한 해석인지 가리는 표지라서다(Result.onUpdate 와 같은 이유).
	 */
	@PreUpdate
	protected void onUpdate() {
		if (status == ResultStatus.PROCESSING) {
			return;
		}
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

	/**
	 * 해석 중이고, 그 해석을 startedAt 에 시작했는지 본다. Result.isProcessingStartedAt 과 같은 규칙이다. 오래 멈춰 되돌려졌거나
	 * 그 뒤 같은 결제로 해석을 다시 시작했으면 false 다.
	 */
	public boolean isProcessingStartedAt(LocalDateTime startedAt) {
		return status == ResultStatus.PROCESSING && updatedAt != null && updatedAt.equals(startedAt);
	}

	/**
	 * 궁합 본문과 점수, 요약을 넣고 완료(COMPLETED)로 바꾼다. 자기가 시작한 해석인지는 부르는 쪽이 {@link #isProcessingStartedAt}
	 * 으로 먼저 가린다. 그래서 되돌린 뒤 다시 시작한 결과에 먼저 시작한 해석의 본문이 붙지 않는다.
	 *
	 * @throws IllegalStateException 해석 중(PROCESSING)이 아닐 때. 이미 완료된 결과를 다시 덮어쓰는 저장을 막는다.
	 */
	public void completeInterpretation(String interpretation, Integer score, String summary) {
		requireProcessing("해석 결과를 저장할");
		this.interpretation = interpretation;
		this.compatibilityScore = score;
		this.summary = summary;
		this.status = ResultStatus.COMPLETED;
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

	/**
	 * 해석을 시작하며 두 사람의 이름과 일간을 채운다. 상태와 해석을 시작한 시각(updated_at)은 바꾸지 않는다.
	 *
	 * <p>해석 중(PROCESSING)으로 바꾸는 일은 CompatibilityResultRepository.markProcessingIfInputRequired 의 조건부 UPDATE 가
	 * 맡고, 이 메서드는 그 관문을 지난 결과에만 쓴다. 자기가 시작한 해석인지는 부르는 쪽이 {@link #isProcessingStartedAt} 으로 먼저
	 * 가린다.
	 *
	 * @throws IllegalStateException 해석 중이 아닐 때. 완료된 결과에 다른 사람의 정보를 덮어써 본문과 인적 정보가 어긋나지 않게 한다.
	 */
	public void updatePersonsInformation(
		String person1Name,
		String person1Ilgan,
		String person2Name,
		String person2Ilgan
	) {
		requireProcessing("두 사람의 정보를 채울");
		this.person1Name = person1Name;
		this.person1Ilgan = person1Ilgan;
		this.person2Name = person2Name;
		this.person2Ilgan = person2Ilgan;
	}

	private void requireProcessing(String action) {
		if (status != ResultStatus.PROCESSING) {
			throw new IllegalStateException(String.format(
				"해석 중(PROCESSING)인 궁합 결과에만 %s 수 있다. paymentId=%s, 지금 상태=%s", action, paymentId, status));
		}
	}
}
