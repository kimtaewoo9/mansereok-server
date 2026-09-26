package com.mansereok.server.domain.review.service;

import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.review.dto.request.ReviewCreateRequest;
import com.mansereok.server.domain.review.dto.response.ReviewEligibilityResponse;
import com.mansereok.server.domain.review.dto.response.ReviewResponse;
import com.mansereok.server.domain.review.entity.Review;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.user.entity.Role;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.ReviewNotAllowedException;
import com.mansereok.server.global.exception.UniqueConstraintViolations;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ReviewService {

	/**
	 * 로그인하지 않은 사람도 보는 리뷰 목록(GET /api/v1/reviews)이 한 번에 돌려주는 최대 건수. 최신 리뷰부터 이만큼만 읽고, 더 오래된
	 * 리뷰는 돌려주지 않는다. 리뷰가 쌓여도 요청 한 번이 읽는 행 수와 응답 크기가 이 값을 넘지 않는다.
	 */
	public static final int PUBLIC_REVIEW_LIMIT = 100;

	/** 페이지 단위 리뷰 목록(GET /api/v1/reviews/pagination)에서 한 페이지에 담을 수 있는 최대 건수. */
	public static final int MAX_PAGE_SIZE = 50;

	private final ReviewRepository reviewRepository;
	private final OrderRepository orderRepository; //
	private final UserService userService; //
	private final Clock clock;

	/**
	 * 상품의 리뷰를 최신순으로 최대 {@value #PUBLIC_REVIEW_LIMIT}건 돌려준다. 같은 시각에 쓴 리뷰는 id 가 작은 것이 앞에 온다.
	 */
	public List<ReviewResponse> getReviewsBySubCategory(Long subCategoryId) {
		return toResponses(
			reviewRepository.findReviewsBySubCategoryWithPagination(subCategoryId, 0, PUBLIC_REVIEW_LIMIT));
	}

	/**
	 * 모든 상품의 리뷰를 최신순으로 최대 {@value #PUBLIC_REVIEW_LIMIT}건 돌려준다. 같은 시각에 쓴 리뷰는 id 가 작은 것이 앞에 온다.
	 */
	public List<ReviewResponse> getAllReviewsSortedByLatest() {
		return toResponses(reviewRepository.findAllReviewsWithPagination(0, PUBLIC_REVIEW_LIMIT));
	}

	/**
	 * 회원이 주문 하나로 리뷰를 쓴다. 본문 길이는 요청 검증과 {@link Review#write} 가 본다.
	 *
	 * @throws ReviewNotAllowedException 이 주문으로 리뷰를 쓸 수 없을 때. 같은 주문으로 동시에 들어온 요청이 먼저 저장했으면
	 *                                   ALREADY_WRITTEN(409) 이다.
	 */
	@Transactional
	public ReviewResponse createReview(String username, ReviewCreateRequest request) {
		User user = userService.findByUsername(username);

		// 자격 조회 API 와 같은 규칙으로 판단하고, 쓸 수 없으면 그 이유로 거절한다.
		Order order = orderRepository.findById(request.getOrderId())
			.orElseThrow(() -> new ReviewNotAllowedException(RejectionReason.ORDER_NOT_FOUND));
		findRejection(order, user, request.getSubCategoryId()).ifPresent(reason -> {
			throw new ReviewNotAllowedException(reason);
		});

		Review savedReview = saveNewReview(Review.write(user, order, request.getContent()));
		return ReviewResponse.from(savedReview);
	}

	/**
	 * 새 리뷰를 INSERT 하고 바로 DB 에 보낸다. 이미 쓴 리뷰가 있는지는 앞에서 잠금 없이 확인하므로, 같은 주문으로 동시에 들어온 두
	 * 요청이 둘 다 그 확인을 지나칠 수 있다. 그때는 reviews 의 UNIQUE(uk_reviews_order_id)가 두 번째 INSERT 를 막는다.
	 *
	 * <p>UNIQUE 위반일 때만 ALREADY_WRITTEN 으로 바꿔 409 가 되게 하고, NOT NULL·길이 초과처럼 다른 위반은 그대로 던진다. 잡은 뒤
	 * 정상으로 돌아가면 안 된다. 저장소 호출이 이미 트랜잭션을 롤백하기로 표시해 두어, 커밋할 때 UnexpectedRollbackException 이 난다.
	 */
	private Review saveNewReview(Review review) {
		try {
			return reviewRepository.saveAndFlush(review);
		} catch (DataIntegrityViolationException e) {
			if (UniqueConstraintViolations.isUniqueViolation(e)) {
				throw new ReviewNotAllowedException(RejectionReason.ALREADY_WRITTEN, e);
			}
			throw e;
		}
	}

	/**
	 * 리뷰를 논리적으로 삭제한다(행은 남기고 is_deleted 를 true 로 바꾼다). 관리자(ADMIN, SUPER_ADMIN)만 할 수 있다.
	 *
	 * <p>권한은 리뷰를 찾기 전에 확인한다. 그래야 관리자가 아닌 사람이 403 과 404 의 차이로 리뷰 번호가 있는지 알아낼 수 없다.
	 * 역할은 토큰에 적힌 값이 아니라 DB 의 현재 값을 본다.
	 *
	 * @throws AccessDeniedException   요청자가 관리자가 아닐 때(403)
	 * @throws EntityNotFoundException 요청자나 리뷰가 없을 때(404)
	 */
	@Transactional
	public void deleteReview(Long reviewId, String requesterUsername) {
		User requester = userService.findByUsername(requesterUsername);
		if (!canDeleteReview(requester.getRole())) {
			throw new AccessDeniedException("리뷰는 관리자만 삭제할 수 있습니다.");
		}

		Review review = reviewRepository.findById(reviewId)
			.orElseThrow(() -> new EntityNotFoundException("해당 리뷰를 찾을 수 없습니다: " + reviewId));

		// 이 트랜잭션에서 조회한 엔티티라 따로 save 하지 않아도 커밋할 때 바뀐 값이 반영된다.
		review.markAsDeleted();
	}

	private static boolean canDeleteReview(Role role) {
		return role == Role.ADMIN || role == Role.SUPER_ADMIN;
	}

	/**
	 * 주문으로 리뷰를 쓸 수 있는지 알려준다. 쓸 수 없어도 예외 없이 이유를 담아 답한다. 규칙은 {@link ReviewEligibilityPolicy} 에
	 * 있고, 작성 API 도 같은 규칙으로 거절한다.
	 */
	public ReviewEligibilityResponse checkReviewEligibility(String username, Long orderId,
		Long subCategoryId) {
		User user = userService.findByUsername(username);

		return orderRepository.findById(orderId)
			.map(order -> findRejection(order, user, subCategoryId)
				.map(ReviewEligibilityResponse::rejected)
				.orElseGet(ReviewEligibilityResponse::allowed))
			.orElseGet(() -> ReviewEligibilityResponse.rejected(RejectionReason.ORDER_NOT_FOUND));
	}

	private Optional<RejectionReason> findRejection(Order order, User requester, Long subCategoryId) {
		return ReviewEligibilityPolicy.findRejection(order, requester.getId(), subCategoryId,
			LocalDate.now(clock), () -> reviewRepository.existsByOrderId(order.getId()));
	}

	/**
	 * 상품의 리뷰를 최신순으로 나눈 page 번째 페이지를 돌려준다. page 는 1부터 센다. 같은 시각에 쓴 리뷰는 id 가 작은 것이 앞에 와서,
	 * 페이지를 차례로 넘겨도 같은 리뷰가 두 번 나오거나 빠지지 않는다(그 사이에 리뷰가 새로 쓰이거나 지워지지 않았다면).
	 *
	 * @throws IllegalArgumentException page 가 1보다 작거나, size 가 1보다 작거나 {@value #MAX_PAGE_SIZE}보다 클 때(400).
	 *                                  이때는 쿼리를 보내지 않는다.
	 */
	public Page<ReviewResponse> getReviewsBySubCategory(Long subCategoryId, int page, int size) {
		return readPage(page, size,
			(offset, limit) -> reviewRepository.findReviewsBySubCategoryWithPagination(subCategoryId, offset, limit),
			() -> reviewRepository.countBySubCategory(subCategoryId));
	}

	/**
	 * 모든 상품의 리뷰를 최신순으로 나눈 page 번째 페이지를 돌려준다. 순서와 page·size 규칙은
	 * {@link #getReviewsBySubCategory(Long, int, int)} 와 같다.
	 *
	 * @throws IllegalArgumentException page 가 1보다 작거나, size 가 1보다 작거나 {@value #MAX_PAGE_SIZE}보다 클 때(400).
	 *                                  이때는 쿼리를 보내지 않는다.
	 */
	public Page<ReviewResponse> getAllReviewsSortedByLatest(int page, int size) {
		return readPage(page, size, reviewRepository::findAllReviewsWithPagination, reviewRepository::countAllReviews);
	}

	/**
	 * page·size 를 먼저 확인한 뒤 그 페이지의 리뷰와 전체 건수를 읽어 Page 로 묶는다. 돌려주는 Page 의 번호(number)는 0부터 센다.
	 */
	private Page<ReviewResponse> readPage(int page, int size, ReviewPageQuery pageQuery, LongSupplier countQuery) {
		checkPageRange(page, size);
		// int 끼리 곱하면 page 가 클 때 넘쳐 음수가 되므로 long 으로 곱한다.
		long offset = (long) (page - 1) * size;
		List<ReviewResponse> content = toResponses(pageQuery.find(offset, size));
		return new PageImpl<>(content, PageRequest.of(page - 1, size), countQuery.getAsLong());
	}

	// 잘못된 값이 그대로 쿼리로 가면 page=0 은 음수 OFFSET 으로 SQL 오류(500)가 나고, 큰 size 는 리뷰 전체를 한 번에 읽는다.
	private static void checkPageRange(int page, int size) {
		if (page < 1) {
			throw new IllegalArgumentException("페이지 번호(page)는 1 이상이어야 합니다.");
		}
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw new IllegalArgumentException("페이지 크기(size)는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
		}
	}

	private static List<ReviewResponse> toResponses(List<Review> reviews) {
		return reviews.stream()
			.map(ReviewResponse::from)
			.toList();
	}

	/** 최신순으로 늘어선 리뷰에서 앞의 offset 건을 건너뛰고 limit 건을 읽는 쿼리. */
	@FunctionalInterface
	private interface ReviewPageQuery {

		List<Review> find(long offset, int limit);
	}
}
