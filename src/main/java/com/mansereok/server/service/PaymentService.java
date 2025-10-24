package com.mansereok.server.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.entity.Order;
import com.mansereok.server.entity.OrderStatus;
import com.mansereok.server.entity.Payment;
import com.mansereok.server.entity.PaymentStatus;
import com.mansereok.server.entity.SubCategory;
import com.mansereok.server.entity.User;
import com.mansereok.server.exception.PaymentException;
import com.mansereok.server.repository.OrderRepository;
import com.mansereok.server.repository.PaymentRepository;
import com.mansereok.server.repository.SubCategoryRepository;
import com.mansereok.server.repository.UserRepository;
import com.mansereok.server.service.request.OrderCreateRequest;
import com.mansereok.server.service.request.PaymentCompleteRequest;
import com.mansereok.server.service.response.OrderCreateResponse;
import com.mansereok.server.service.response.PaymentResponseDto;
import com.mansereok.server.service.response.PortOnePaymentResponse;
import com.mansereok.server.service.response.PortoneWebhookDto;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class PaymentService {

	private final OrderRepository orderRepository;
	private final SubCategoryRepository subCategoryRepository;
	private final PaymentRepository paymentRepository;
	private final UserService userService;

	private final ObjectMapper objectMapper;
	private final UserRepository userRepository;

	private final RestClient restClient = RestClient.create();

	@Value("${portone.api.secret}")
	private String portOneApiSecret;

	// 1단계: 주문 생성 (결제 전)
	public OrderCreateResponse createOrder(String username, OrderCreateRequest request) {
		log.info("주문 생성 요청: subCategoryId={}", request.getSubCategoryId());
		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new PaymentException("사용자를 찾을 수 없습니다."));

		SubCategory subCategory = subCategoryRepository.findById(request.getSubCategoryId())
			.orElseThrow(() -> new PaymentException("존재하지 않는 상품입니다."));

		Integer amount = subCategory.getPrice(); // 2. 금액 계산 .

		// TODO  중복 구매 체크
		Long currentUserId = user.getId();
		boolean alreadyPurchased = orderRepository.existsByUserIdAndSubCategoryIdAndStatus(
			currentUserId, subCategory.getId(), OrderStatus.PAID
		);
		if (alreadyPurchased) {
			throw new PaymentException("이미 구매한 항목입니다.");
		}

		// 4. 결제 회사에 보여주는 영수증 번호 .. merchantUid
		String merchantUid =
			"order_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString()
				.substring(0, 8);

		// 5. 주문을 DB에 저장함 .
		Order savedOrder = orderRepository.save(
			Order.create(
				merchantUid,
				user.getId(),
				subCategory.getId(),
				amount,
				OrderStatus.PENDING
			)
		);

		log.info("주문 생성 완료: orderId={}, merchantUid={}, amount={}",
			savedOrder.getId(), merchantUid, amount);

		return new OrderCreateResponse(
			savedOrder.getId(),
			merchantUid,
			amount,
			subCategory.getTitle()
		);
	}

	// 2단계. 결제 상태 조회 ..
	public Order completePayment(PaymentCompleteRequest request) {
		log.info("결제 상태 조회: paymentId={}, merchantUid={}",
			request.getPaymentId(), request.getMerchantUid());

		// 락 불필요 - 조회만
		Order order = orderRepository.findByMerchantUid(request.getMerchantUid())
			.orElseThrow(() -> new PaymentException("주문을 찾을 수 없습니다."));

		// 포트원 API 조회 (검증용)
		PortOnePaymentResponse paymentResponse =
			fetchPaymentDataFromPortOne(request.getPaymentId());

		// 금액 검증
		if (!Objects.equals(paymentResponse.getAmount().getTotal(),
			order.getAmount().longValue())) {
			throw new PaymentException("결제 금액이 일치하지 않습니다.");
		}

		// 상태만 반환 (DB 업데이트 안함!)
		return order;
	}

	public void processWebhook(String body) {
		try {
			PortoneWebhookDto webhook = objectMapper.readValue(body, PortoneWebhookDto.class);

			log.info("웹훅 수신: type={}, paymentId={}",
				webhook.getType(), webhook.getData().getPaymentId());

			String paymentId = webhook.getData().getPaymentId();
			String merchantUid = webhook.getData().getOrderDetail().getOrderNo();
			String webhookType = webhook.getType();

			if (!"Transaction.Paid".equals(webhookType)) {
				log.info("결제 완료 이벤트가 아님: type={}", webhookType);
				return;
			}

			// 1. 주문 조회
			Order order = orderRepository.findByMerchantUid(merchantUid)
				.orElseThrow(() -> new PaymentException("주문을 찾을 수 없습니다: " + merchantUid));
			if (order.getStatus() == OrderStatus.PAID) {
				log.info("이미 처리된 주문: merchantUid={}", merchantUid);
				return;
			}

			// 포트원에 결제 됐는지 재확인함
			PortOnePaymentResponse paymentResponse = fetchPaymentDataFromPortOne(paymentId);

			// 2. 금액 검증
			if (!Objects.equals(paymentResponse.getAmount().getTotal(), order.getAmount())) {
				log.error("웹훅 금액 불일치: expected={}, actual={}",
					order.getAmount(), paymentResponse.getAmount().getTotal());
				order.setStatus(OrderStatus.FAILED);
				orderRepository.save(order);
				throw new PaymentException("결제 금액이 일치하지 않습니다.");
			}

			PaymentStatus status = PaymentStatus.fromPortOneStatus(paymentResponse.getStatus());

			if (status == PaymentStatus.PAID) {
				order.setStatus(OrderStatus.PAID);
				order.setPaymentId(paymentId);
				order.setPaidAt(LocalDateTime.now());

				Order savedOrder = orderRepository.save(order);
				log.info("웹훅으로 결제 완료 처리: orderId={}, paymentId={}",
					savedOrder.getId(), paymentId);

				// payment 저장 .
				paymentRepository.save(
					Payment.create(
						paymentId,
						merchantUid,
						paymentResponse.getAmount().getTotal(),
						status,
						savedOrder.getId(),
						savedOrder.getUserId()
					)
				);

				processOrder(savedOrder);
			} else {
				log.error("웹훅 결제 실패: paymentId={}, status={}", paymentId, status);
				order.setStatus(OrderStatus.FAILED);
				orderRepository.save(order);
				throw new PaymentException("결제 실패 상태입니다.");
			}
		} catch (Exception e) {
			log.error("웹훅 처리 중 에러", e); // json 파싱 에러일 수 있음 .
			throw new PaymentException("웹훅 처리 실패: " + e.getMessage());
		}
	}

	public List<PaymentResponseDto> getPayments(String username) {
		User user = userRepository.findByUsername(username)
			.orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

		return paymentRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId())
			.stream()
			.map(PaymentResponseDto::create)
			.collect(Collectors.toList());
	}

	private PortOnePaymentResponse fetchPaymentDataFromPortOne(String paymentId) {
		try {
			String url = "https://api.portone.io/payments/" + paymentId;
			return restClient.get()
				.uri(url)
				.header(HttpHeaders.AUTHORIZATION, "PortOne " + portOneApiSecret)
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(PortOnePaymentResponse.class);
		} catch (Exception e) {
			log.error("포트원 API 호출 실패: paymentId={}", paymentId, e);
			throw new PaymentException("결제 정보를 조회하는 중 오류가 발생했습니다.");
		}
	}

	private void processOrder(Order order) {
		// TODO: 실제 비즈니스 로직 구현
		// - 이메일 발송
		// - 해석 정보 전달 등등 ..

		log.info("주문 처리 완료: orderId	={}, subCategoryId={}",
			order.getId(), order.getSubCategoryId());
	}
}
