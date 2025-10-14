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
		// Long currentUserId = getCurrentUserId();
		// boolean alreadyPurchased = orderRepository.existsByUserIdAndSubCategoryIdAndStatus(
		//     currentUserId, subCategory.getId(), OrderStatus.PAID
		// );
		// if (alreadyPurchased) {
		//     throw new PaymentException("이미 구매한 항목입니다.");
		// }

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

	// 2단계. 결제 완료 후 검증단계
	public Order completePayment(PaymentCompleteRequest request) {
		log.info("결제 검증 요청: paymentId={}, orderId={}",
			request.getPaymentId(), request.getMerchantUid());

		// 1. 먼저 merchantUid 를 통해 Order(주문정보)를 가지고옴 .
		Order order = orderRepository.findByMerchantUid(request.getMerchantUid())
			.orElseThrow(() -> new PaymentException("주문을 찾을 수 없습니다."));

		if (order.getStatus() == OrderStatus.PAID) {
			log.warn("이미 결제 완료된 주문: merchantUid={}", request.getMerchantUid());
			throw new PaymentException("이미 처리된 결제입니다.");
		}

		// 2. DB에서 포트원에서 결제 정보를 가져옴 .
		PortOnePaymentResponse paymentResponse =
			fetchPaymentDataFromPortOne(request.getPaymentId());

		log.info("=== 결제 금액 비교 ===");
		log.info("포트원 금액: {} (타입: {})", paymentResponse.getAmount(),
			paymentResponse.getAmount().getClass());
		log.info("주문 금액: {} (타입: {})", order.getAmount(), order.getAmount().getClass());

		// 3. 주문 정보랑 포트원에서 가져온 정보랑 비교해서 금액을 검증함
		if (!Objects.equals(paymentResponse.getAmount().getTotal(),
			order.getAmount().longValue())) {
			order.setStatus(OrderStatus.FAILED);
			orderRepository.save(order);
			// 에러 메시지도 조금 더 명확하게 수정하면 좋습니다.
			throw new PaymentException(
				String.format("결제 금액 위변조 의심: [DB: %d] != [PortOne: %d]",
					order.getAmount(), paymentResponse.getAmount().getTotal())
			);
		}

		// 4. 결제 상태 확인 .. response로 받은 String 형식의 Status를 Enum으로 바꿈
		PaymentStatus status = PaymentStatus.fromPortOneStatus(paymentResponse.getStatus());

		if (status == PaymentStatus.PAID) {
			// 결제 완료 .. order 업데이트 후 DB에 저장하고 Payment 엔티티 생성

			order.setStatus(OrderStatus.PAID);
			order.setPaymentId(request.getPaymentId());
			order.setPaidAt(LocalDateTime.now());

			Order savedOrder = orderRepository.save(order); //
			log.info("결제 완료 처리 성공: orderId={}, paymentId={}",
				savedOrder.getId(), request.getPaymentId());

			// 금액 검증만하고 PaymentEntity 는 생성하지 않음 .

			processOrder(savedOrder);
			return savedOrder; // 결제 성공시 주문 내역 반환 .
		} else {
			log.error("결제 실패: paymentId={}, status={}", request.getPaymentId(),
				paymentResponse.getStatus());

			order.setStatus(OrderStatus.FAILED);
			orderRepository.save(order);

			throw new PaymentException("결제에 실패했습니다.");
		}
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
