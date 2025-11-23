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

@Table(name = "reviews",
	uniqueConstraints = {
		@UniqueConstraint(columnNames = "order_id") // 하나의 주문에 하나의 리뷰만 가능
	},
	indexes = {
		// 1. 특정 상품 리뷰 조회 및 최신순 정렬 (subCategoryId, is_deleted, createdAt DESC)
		@Index(name = "idx_subcategories_del_created", columnList = "sub_category_id, is_deleted, created_at DESC"),

		// 2. 전체 리뷰 최신순 정렬 (is_deleted, createdAt DESC)
		@Index(name = "idx_del_created", columnList = "is_deleted, created_at DESC"),

		// 3. 사용자별 리뷰 조회 및 최신순 정렬
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

	@Column(name = "order_id", nullable = false, unique = true)
	private Long orderId; // 해당 리뷰가 어떤 주문을 기반으로 작성되었는지

	@Column(columnDefinition = "TEXT", nullable = false)
	private String content; // 리뷰 내용 (최소 20자)

	@Column(name = "user_name")
	private String userName; // 조회를 빠르게 하기 위해 사용자 이름도 저장

	@Column(name = "is_deleted", nullable = false)
	private boolean isDeleted = false; // 삭제는 관리자만 (논리적 삭제)

	private LocalDateTime createdAt;
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
		String userName) {
		Review review = new Review();
		review.userId = userId;
		review.subCategoryId = subCategoryId;
		review.orderId = orderId;
		review.content = content;
		review.userName = userName;
		return review;
	}

	public void markAsDeleted() {
		this.isDeleted = true;
	}
}
