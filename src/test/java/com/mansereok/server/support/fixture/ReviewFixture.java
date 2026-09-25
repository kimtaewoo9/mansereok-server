package com.mansereok.server.support.fixture;

import com.mansereok.server.domain.review.entity.Review;
import java.time.LocalDateTime;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 테스트용 Review 를 만든다. id 와 created_at 은 DB 에 저장할 때 채워지는 값이라, 저장하지 않은 리뷰에 넣으려면 리플렉션이
 * 필요하다. 그 우회를 이 클래스 한 곳에만 둔다.
 *
 * <p>테스트는 결과에 영향을 주는 값만 바꾸고 나머지는 기본값을 쓴다. 호출할 때마다 새 빌더를 돌려주므로 테스트끼리 값이 섞이지 않는다.
 */
public final class ReviewFixture {

	private Long id = 1L;
	private Long userId = 10L;
	private Long subCategoryId = 3L;
	private Long orderId = 100L;
	private String content = "풀이가 자세하고 이해하기 쉬워서 많은 도움이 되었습니다.";
	private String userName = "홍길동";
	private String userEmail = "hong@example.com";
	private LocalDateTime createdAt = LocalDateTime.of(2026, 9, 1, 10, 0);

	private ReviewFixture() {
	}

	/** 삭제되지 않은 보통 리뷰. */
	public static ReviewFixture review() {
		return new ReviewFixture();
	}

	public ReviewFixture id(Long id) {
		this.id = id;
		return this;
	}

	public ReviewFixture userId(Long userId) {
		this.userId = userId;
		return this;
	}

	public ReviewFixture subCategoryId(Long subCategoryId) {
		this.subCategoryId = subCategoryId;
		return this;
	}

	public ReviewFixture orderId(Long orderId) {
		this.orderId = orderId;
		return this;
	}

	public ReviewFixture content(String content) {
		this.content = content;
		return this;
	}

	public ReviewFixture userName(String userName) {
		this.userName = userName;
		return this;
	}

	public ReviewFixture userEmail(String userEmail) {
		this.userEmail = userEmail;
		return this;
	}

	public ReviewFixture createdAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
		return this;
	}

	/** DB 에 저장할 때는 id 와 created_at 을 비워 둔다(IDENTITY 와 @PrePersist 가 채운다). */
	public ReviewFixture forSaving() {
		this.id = null;
		this.createdAt = null;
		return this;
	}

	public Review build() {
		Review review = Review.create(userId, subCategoryId, orderId, content, userName, userEmail);
		ReflectionTestUtils.setField(review, "id", id);
		ReflectionTestUtils.setField(review, "createdAt", createdAt);
		return review;
	}
}
