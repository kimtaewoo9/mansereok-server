package com.mansereok.server.domain.review.service;

import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.review.dto.request.ReviewCreateRequest;
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
import java.util.Optional;
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

		Long eligibleOrderId = findEligibleOrderId(user.getId(), request.getSubCategoryId());

		// 3. 주문 정보를 다시 조회
		Order eligibleOrder = orderRepository.findById(eligibleOrderId)
			.orElseThrow(() -> new PaymentException("유효한 주문 정보를 찾았으나 즉시 조회 실패. DB 상태 확인 필요."));

		// --- 리뷰 저장 (찾은 Order ID 사용) ---
		Review newReview = Review.create(
			user.getId(),
			request.getSubCategoryId(),
			eligibleOrderId,
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

	private Long findEligibleOrderId(Long userId, Long subCategoryId) {
		// 1. PAID 상태인 모든 주문 목록을 최신 순으로 가져옵니다.
		List<Order> paidOrders = orderRepository.findPaidOrdersForReview(userId, subCategoryId);

		if (paidOrders.isEmpty()) {
			throw new PaymentException("해당 상품에 대한 유효한 구매 이력이 없습니다.");
		}

		// 2. 각 주문에 대해 리뷰 가능 여부를 확인합니다. (가장 최근의 유효한 주문을 찾음)
		Optional<Order> eligibleOrder = paidOrders.stream()
			.filter(order -> {
				// 2-a. 구매 후 30일 이내인지 확인
				// LocalDateTime 기준으로 날짜만 비교하기 위해 ChronoUnit.DAYS 사용
				if (order.getPaidAt() == null ||
					ChronoUnit.DAYS.between(order.getPaidAt().toLocalDate(),
						LocalDateTime.now().toLocalDate()) > REVIEW_DEADLINE_DAYS) {
					log.debug("주문 ID {}는 구매 기한(30일) 초과로 리뷰 불가능. PaidAt: {}", order.getId(),
						order.getPaidAt());
					return false;
				}

				// 2-b. 해당 주문으로 이미 리뷰가 작성되었는지 확인
				if (reviewRepository.existsByOrderId(order.getId())) {
					log.debug("주문 ID {}는 이미 리뷰가 작성되어 리뷰 불가능.", order.getId());
					return false;
				}

				return true;
			})
			.findFirst(); // 최신 순으로 정렬했으므로, 처음 발견된 것이 가장 최근에 리뷰 가능한 주문입니다.

		return eligibleOrder.map(Order::getId).orElseThrow(() ->
			new PaymentException(
				"해당 상품에 대해 리뷰 가능한 주문(구매 후 30일 이내 & 미작성)이 없습니다. 여러 번 구매했더라도 리뷰는 한 번만 가능합니다.")
		);
	}
}
