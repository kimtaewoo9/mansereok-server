package com.mansereok.server.domain.review.entity;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.user.entity.User;
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
import java.util.Objects;
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

	/** 리뷰 본문의 최소 글자 수. 앞뒤 공백은 세지 않는다. 요청 검증(@Size)도 이 값을 쓴다. */
	public static final int MIN_CONTENT_LENGTH = 20;

	/**
	 * 리뷰 본문의 최대 글자 수. content 는 TEXT(65,535바이트)라 한글(utf8mb4 로 3바이트)을 2만 자 넘게 넣으면 DB 가 거절한다. 그보다
	 * 훨씬 작게 잡아 DB 오류가 나기 전에 요청 검증에서 막는다.
	 */
	public static final int MAX_CONTENT_LENGTH = 2000;

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
	private String content;

	@Column(name = "user_name")
	private String userName; // 조회를 빠르게 하기 위해 사용자 이름도 저장

	// 예전에 쓴 리뷰에만 값이 있다. 리뷰 목록에 쓰지 않는 개인정보라 새 리뷰에는 저장하지 않는다.
	@Column(name = "user_email")
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

	/**
	 * 회원이 주문 하나로 쓴 새 리뷰를 만든다. 작성자 id·이름은 회원에서, 상품 id·주문 id 는 주문에서 꺼낸다. 이 주문으로 리뷰를 써도
	 * 되는지(본인 주문, 결제 완료, 기한, 이미 쓴 리뷰)는 부르는 쪽이 먼저 확인한다.
	 *
	 * @param content 리뷰 본문. 그대로 저장한다.
	 * @throws IllegalArgumentException 본문이 없거나, 앞뒤 공백을 뺀 길이가 {@value #MIN_CONTENT_LENGTH}자보다 짧거나,
	 *                                  {@value #MAX_CONTENT_LENGTH}자보다 길 때
	 */
	public static Review write(User author, Order order, String content) {
		Objects.requireNonNull(author, "author");
		Objects.requireNonNull(order, "order");
		checkContentLength(content);

		Review review = new Review();
		review.userId = author.getId();
		review.userName = author.getName();
		review.subCategoryId = order.getSubCategoryId();
		review.orderId = order.getId();
		review.content = content;
		return review;
	}

	// 요청 검증(@NotBlank, @Size)은 공백을 글자로 센다. 여기서는 앞뒤 공백을 빼고 세어, 공백으로 길이만 채운 본문도 막는다.
	private static void checkContentLength(String content) {
		if (content == null || content.strip().length() < MIN_CONTENT_LENGTH) {
			throw new IllegalArgumentException("리뷰 내용은 앞뒤 공백을 빼고 최소 " + MIN_CONTENT_LENGTH + "자 이상이어야 합니다.");
		}
		if (content.length() > MAX_CONTENT_LENGTH) {
			throw new IllegalArgumentException("리뷰 내용은 최대 " + MAX_CONTENT_LENGTH + "자까지 쓸 수 있습니다.");
		}
	}

	public void markAsDeleted() {
		this.isDeleted = true;
	}
}
