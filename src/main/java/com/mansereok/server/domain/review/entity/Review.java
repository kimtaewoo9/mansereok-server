package com.mansereok.server.domain.review.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

// 운영은 ddl-auto: validate 라 아래 UNIQUE·인덱스를 만들지도 검사하지도 않는다. 운영에는 schema.sql 의 reviews 와 같은 이름으로
// 손으로 적용한다. 이름을 고정해 두어야 로컬(ddl-auto: update)과 운영의 이름이 같아지고, EXPLAIN 결과를 서로 견줄 수 있다.
@Table(name = "reviews",
	uniqueConstraints = {
		// 주문 하나에 리뷰 하나. 같은 주문으로 두 요청이 동시에 와서 둘 다 existsByOrderId 를 통과해도 두 번째 INSERT 를 막는다.
		@UniqueConstraint(name = "uk_reviews_order_id", columnNames = "order_id")
	},
	indexes = {
		// 상품별 리뷰 목록·페이지·개수. 안쪽 SELECT id 는 이 인덱스만 읽고(id 는 인덱스에 함께 들어 있다) 정렬도 하지 않는다.
		@Index(name = "idx_subcat_del_created", columnList = "sub_category_id, is_deleted, created_at DESC"),

		// 전체 리뷰 페이지·개수. 위와 같은 방식으로 이 인덱스만 읽는다.
		@Index(name = "idx_del_created", columnList = "is_deleted, created_at DESC"),

		// 회원 탈퇴 때 그 회원의 리뷰를 지우는 DELETE ... WHERE user_id 가 쓴다. 이 인덱스가 없으면 DELETE 가 표 전체를 훑는다.
		// 탈퇴는 READ COMMITTED 로 돌아 다른 회원의 리뷰 작성을 막지는 않는다. 대신 다른 회원이 아직 커밋하지 않은 리뷰 행을 만나면
		// 그 트랜잭션이 끝날 때까지 기다린다.
		@Index(name = "idx_user_del_created", columnList = "user_id, is_deleted, created_at DESC")
	}
)
@Entity
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "sub_category_id", nullable = false)
	private Long subCategoryId;

	@Column(name = "order_id", nullable = false)
	private Long orderId; // 해당 리뷰가 어떤 주문을 기반으로 작성되었는지

	@Column(columnDefinition = "TEXT", nullable = false)
	private String content; // 리뷰 내용 (최소 20자)

	@Column(name = "user_name")
	private String userName; // 조회를 빠르게 하기 위해 사용자 이름도 저장

	@Column(name = "user_email") // [추가] 이메일 컬럼 추가
	private String userEmail;

	// 관리자가 지운 리뷰는 행을 남기고 true 로 표시한다(논리적 삭제). 목록 조회는 false 인 리뷰만 보여 준다.
	// 권한 검사는 ReviewService.deleteReview 가 한다. 회원 탈퇴 때는 이 표시와 상관없이 그 회원의 리뷰 행을 실제로 지운다.
	@Column(name = "is_deleted", nullable = false)
	private boolean isDeleted = false;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	@PrePersist
	protected void onCreate() {
		this.createdAt = LocalDateTime.now();
		this.updatedAt = LocalDateTime.now();
	}

	@PreUpdate
	protected void onUpdate() {
		this.updatedAt = LocalDateTime.now();
	}

	public static Review create(Long userId, Long subCategoryId, Long orderId, String content,
		String userName, String userEmail) {
		Review review = new Review();
		review.userId = userId;
		review.subCategoryId = subCategoryId;
		review.orderId = orderId;
		review.content = content;
		review.userName = userName;
		review.userEmail = userEmail;
		return review;
	}

	public void markAsDeleted() {
		this.isDeleted = true;
	}
}
