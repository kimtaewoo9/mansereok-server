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
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ReviewService {

	private final ReviewRepository reviewRepository;
	private final OrderRepository orderRepository; //
	private final UserService userService; //
	private final Clock clock;

	private static final int MIN_CONTENT_LENGTH = 20; // 최소 20자 ..


	public List<ReviewResponse> getReviewsBySubCategory(Long subCategoryId) {
		List<Review> reviews = reviewRepository.findReviewsBySubCategory(
			subCategoryId);
		return reviews.stream()
			.map(ReviewResponse::from)
			.collect(Collectors.toList());
	}

	public List<ReviewResponse> getAllReviewsSortedByLatest() {
		List<Review> reviews = reviewRepository.findAllLatestReviews();
		return reviews.stream()
			.map(ReviewResponse::from)
			.collect(Collectors.toList());
	}

	@Transactional
	public ReviewResponse createReview(String username, ReviewCreateRequest request) {
		User user = userService.findByUsername(username);

		// 길이 체크
		if (request.getContent().length() < MIN_CONTENT_LENGTH) {
			throw new IllegalArgumentException("리뷰 내용은 최소 " + MIN_CONTENT_LENGTH + "자 이상이어야 합니다.");
		}

		// 자격 조회 API 와 같은 규칙으로 판단하고, 쓸 수 없으면 그 이유로 거절한다.
		Order order = orderRepository.findById(request.getOrderId())
			.orElseThrow(() -> new ReviewNotAllowedException(RejectionReason.ORDER_NOT_FOUND));
		findRejection(order, user, request.getSubCategoryId()).ifPresent(reason -> {
			throw new ReviewNotAllowedException(reason);
		});

		// --- 리뷰 저장 (찾은 Order ID 사용) ---
		Review newReview = Review.create(
			user.getId(),
			request.getSubCategoryId(),
			order.getId(),
			request.getContent(),
			user.getName(),
			user.getEmail()
		);
		Review savedReview = reviewRepository.save(newReview);

		// TODO: 리뷰 작성 보상 지급
//		try {
//			DiscountCode rewardCode = discountCodeService.createReviewRewardCode(user.getId(), REWARD_DISCOUNT_AMOUNT);
//
//			// 사용자에게 이메일로 알림 (EmailService에 메서드 추가 가정)
//			emailService.sendReviewRewardEmail(user.getEmail(), user.getName(), rewardCode.getCode(), REWARD_DISCOUNT_AMOUNT);
//
//		} catch (Exception e) {
//			log.error("리뷰 보상 지급 또는 이메일 전송 실패: userId={}, Error: {}", user.getId(), e.getMessage(), e);
//		}

		return ReviewResponse.from(savedReview);
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

	public Page<ReviewResponse> getReviewsBySubCategory(Long subCategoryId, int page, int size) {
		// Offset 계산
		long offset = (long) (page - 1) * size;

		// 1. 리스트 조회 (커버링 인덱스 쿼리)
		List<Review> reviews = reviewRepository.findReviewsBySubCategoryWithPagination(
			subCategoryId, offset, size
		);

		// 2. 전체 개수 조회
		long totalCount = reviewRepository.countBySubCategory(subCategoryId);

		// 3. 응답 변환
		List<ReviewResponse> content = reviews.stream()
			.map(ReviewResponse::from)
			.collect(Collectors.toList());

		// Spring Data의 Page 인터페이스로 감싸서 반환 (프론트엔드 처리가 용이함)
		Pageable pageable = PageRequest.of(page - 1, size);
		return new PageImpl<>(content, pageable, totalCount);
	}

	public Page<ReviewResponse> getAllReviewsSortedByLatest(int page, int size) {
		long offset = (long) (page - 1) * size;

		// 1. 커버링 인덱스 쿼리로 데이터 조회
		List<Review> reviews = reviewRepository.findAllReviewsWithPagination(offset, size);

		// 2. 전체 개수 조회
		long totalCount = reviewRepository.countAllReviews();

		// 3. DTO 변환
		List<ReviewResponse> content = reviews.stream()
			.map(ReviewResponse::from)
			.collect(Collectors.toList());

		return new PageImpl<>(content, PageRequest.of(page - 1, size), totalCount);
	}
}
