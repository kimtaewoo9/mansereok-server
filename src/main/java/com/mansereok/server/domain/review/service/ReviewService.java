package com.mansereok.server.domain.review.service;

import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.review.dto.request.ReviewCreateRequest;
import com.mansereok.server.domain.review.dto.response.ReviewEligibilityResponse;
import com.mansereok.server.domain.review.dto.response.ReviewResponse;
import com.mansereok.server.domain.review.entity.Review;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.service.EmailService;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.PaymentException;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
	private final DiscountCodeService discountCodeService; //
	private final EmailService emailService; //

	private static final int REVIEW_DEADLINE_DAYS = 30;
	private static final int REWARD_DISCOUNT_AMOUNT = 500;
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
			throw new PaymentException("리뷰 내용은 최소 " + MIN_CONTENT_LENGTH + "자 이상이어야 합니다.");
		}

		Order order = orderRepository.findById(request.getOrderId())
			.orElseThrow(() -> new EntityNotFoundException("주문 정보를 찾을 수 없습니다."));

		// 3. 해당 주문이 리뷰 작성 가능한지 검증
		validateOrderForReview(order, user, request.getSubCategoryId());

		// --- 리뷰 저장 (찾은 Order ID 사용) ---
		Review newReview = Review.create(
			user.getId(),
			request.getSubCategoryId(),
			order.getId(),
			request.getContent(),
			user.getName()
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

	@Transactional
	public void deleteReview(Long reviewId, String adminUsername) {
		User adminUser = userService.findByUsername(adminUsername); //

		if (!"ADMIN".equals(adminUser.getRole().name())) {
			throw new AccessDeniedException("관리자만 리뷰를 삭제할 수 있습니다.");
		}

		Review review = reviewRepository.findById(reviewId)
			.orElseThrow(() -> new EntityNotFoundException("해당 리뷰를 찾을 수 없습니다: " + reviewId));

		// 2. 논리적 삭제 처리
		review.markAsDeleted();
		reviewRepository.save(review);
	}

	public ReviewEligibilityResponse checkReviewEligibility(String username, Long orderId,
		Long subCategoryId) {
		User user = userService.findByUsername(username);

		// 1. 주문 존재 여부 확인
		Order order = orderRepository.findById(orderId).orElse(null);
		if (order == null) {
			return ReviewEligibilityResponse.ineligible(
				ReviewEligibilityResponse.RejectionReason.ORDER_NOT_FOUND,
				"유효하지 않은 주문 정보입니다."
			);
		}

		// 2. 본인의 주문인지 확인
		if (!order.getUserId().equals(user.getId())) {
			return ReviewEligibilityResponse.ineligible(
				ReviewEligibilityResponse.RejectionReason.NOT_OWNER,
				"본인의 주문에 대해서만 리뷰를 작성할 수 있습니다."
			);
		}

		// 3. 상품 일치 여부 확인
		if (!order.getSubCategoryId().equals(subCategoryId)) {
			return ReviewEligibilityResponse.ineligible(
				ReviewEligibilityResponse.RejectionReason.MISMATCH_PRODUCT,
				"주문한 상품 정보와 일치하지 않습니다."
			);
		}

		// 4. 결제 상태 확인
		if (order.getPaidAt() == null) {
			return ReviewEligibilityResponse.ineligible(
				ReviewEligibilityResponse.RejectionReason.NOT_PAID,
				"결제가 완료되지 않은 주문입니다."
			);
		}

		// 5. 기간 확인 (30일 이내)
		long daysSincePayment = ChronoUnit.DAYS.between(order.getPaidAt().toLocalDate(),
			LocalDateTime.now().toLocalDate());
		if (daysSincePayment > REVIEW_DEADLINE_DAYS) {
			return ReviewEligibilityResponse.ineligible(
				ReviewEligibilityResponse.RejectionReason.EXPIRED,
				"구매 후 " + REVIEW_DEADLINE_DAYS + "일이 지나 리뷰를 작성할 수 없습니다."
			);
		}

		// 6. 중복 작성 확인
		if (reviewRepository.existsByOrderId(order.getId())) {
			return ReviewEligibilityResponse.ineligible(
				ReviewEligibilityResponse.RejectionReason.ALREADY_WRITTEN,
				"이미 해당 주문에 대한 리뷰를 작성하셨습니다."
			);
		}

		// 통과!
		return ReviewEligibilityResponse.eligible();
	}

	private void validateOrderForReview(Order order, User user, Long requestedSubCategoryId) {
		// 1. 본인의 주문인지 확인
		if (!order.getUserId().equals(user.getId())) {
			throw new AccessDeniedException("본인의 주문에 대해서만 리뷰를 작성할 수 있습니다.");
		}

		// 2. 요청한 상품(SubCategory)에 대한 주문인지 확인
		if (!order.getSubCategoryId().equals(requestedSubCategoryId)) {
			throw new PaymentException("주문한 상품과 리뷰하려는 상품이 일치하지 않습니다.");
		}

		// 3. 결제 완료 여부 확인
		if (order.getPaidAt() == null) {
			throw new PaymentException("결제가 완료된 주문만 리뷰를 작성할 수 있습니다.");
		}

		// 4. 기간 확인 (30일 이내)
		long daysSincePayment = ChronoUnit.DAYS.between(order.getPaidAt().toLocalDate(),
			LocalDateTime.now().toLocalDate());
		if (daysSincePayment > REVIEW_DEADLINE_DAYS) {
			throw new PaymentException(
				"구매 후 " + REVIEW_DEADLINE_DAYS + "일이 지난 주문은 리뷰를 작성할 수 없습니다.");
		}

		// 5. 중복 리뷰 확인 (이미 해당 주문으로 리뷰가 존재하는지)
		if (reviewRepository.existsByOrderId(order.getId())) {
			throw new PaymentException("해당 주문에 대해 이미 리뷰를 작성했습니다.");
		}
	}
}
