package com.mansereok.server.service;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.entity.CompatibilityResult;
import com.mansereok.server.entity.Order;
import com.mansereok.server.entity.OrderStatus;
import com.mansereok.server.entity.Payment;
import com.mansereok.server.entity.PaymentStatus;
import com.mansereok.server.entity.Result;
import com.mansereok.server.entity.SubCategory;
import com.mansereok.server.entity.User;
import com.mansereok.server.exception.PaymentException;
import com.mansereok.server.repository.CompatibilityResultRepository;
import com.mansereok.server.repository.OrderRepository;
import com.mansereok.server.repository.PaymentRepository;
import com.mansereok.server.repository.ResultRepository;
import com.mansereok.server.repository.SubCategoryRepository;
import com.mansereok.server.repository.UserRepository;
import com.mansereok.server.service.request.OrderCreateRequest;
import com.mansereok.server.service.request.PaymentCompleteRequest;
import com.mansereok.server.service.response.OrderCreateResponse;
import com.mansereok.server.service.response.PaymentResponseDto;
import com.mansereok.server.service.response.PortOnePaymentResponse;
import com.mansereok.server.service.response.PortoneWebhookDto;
import jakarta.persistence.EntityNotFoundException;
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

	private final ResultRepository resultRepository;
	private final CompatibilityResultRepository compatibilityResultRepository;

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
		log.info("결제 상태 조회: paymentId={}, merchantUid={}", request.getPaymentId(),
			request.getMerchantUid());

		Order order = orderRepository.findByMerchantUid(request.getMerchantUid())
			.orElseThrow(() -> new PaymentException("주문을 찾을 수 없습니다."));

		log.info("[PaymentService.completePayment] order: " + order);

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
		String merchantUidFromCustomData = null;
		try {
			log.info("=== 웹훅 원본 페이로드 ===");
			log.info(body);

			PortoneWebhookDto webhook = objectMapper.readValue(body, PortoneWebhookDto.class);

			log.info("웹훅 수신: status={}, paymentId={}",
				webhook.getStatus(), webhook.getPaymentId());

			String paymentId = webhook.getPaymentId();
			String status = webhook.getStatus();
			String merchantUid = webhook.getMerchantUid();

			log.info("webhook.getStatus(): {}", webhook.getStatus());
			log.info("webhook.getPaymentId(): {}", webhook.getPaymentId());
			log.info("webhook.getMerchantUid(): {}", webhook.getMerchantUid());

			// Ready 상태는 결제 완료가 아님 (가상계좌 발급, 결제 시작 등)
			if (!"Paid".equals(status)) {
				log.info("결제 완료 이벤트가 아님: status={}", status);
				return;
			}

			log.info("paymentId '{}'로 주문을 조회합니다...", paymentId);

			// 포트원에 결제 됐는지 재확인함
			PortOnePaymentResponse paymentResponse = fetchPaymentDataFromPortOne(paymentId);
			log.info("PortOnePaymentResponse: " + paymentResponse);

			String customDataString = paymentResponse.getCustomData();
			if (customDataString == null || customDataString.isBlank()) {
				log.error("PortOne API 응답(paymentId:{})에 customData가 비어있습니다!", paymentId);
				throw new PaymentException("결제 API 응답에서 customData를 찾을 수 없어 주문 번호를 알 수 없습니다.");
			}

			try {
				// customData 문자열을 JSON 객체로 파싱
				JsonNode customDataJson = objectMapper.readTree(customDataString);
				// "merchantUid" 필드 값 추출
				if (customDataJson.has("merchantUid")) {
					merchantUidFromCustomData = customDataJson.get("merchantUid").asText();
					log.info("customData에서 merchantUid 추출 성공: {}", merchantUidFromCustomData);
				}

				// 추출한 merchantUid 검증
				if (merchantUidFromCustomData == null || merchantUidFromCustomData.isBlank()) {
					log.error("customData JSON 안에 'merchantUid' 필드가 없거나 비어있습니다! customData: {}",
						customDataString);
					log.error(
						"프론트엔드 customData 형식을 확인하세요. 예: { \"merchantUid\": \"order_...\", ... }");
					throw new PaymentException(
						"결제 API 응답의 customData에서 유효한 주문 번호(merchantUid)를 추출할 수 없습니다.");
				}

			} catch (JsonProcessingException e) {
				log.error("customData 문자열 JSON 파싱 실패! customData: {}", customDataString, e);
				log.error("프론트엔드에서 customData를 올바른 JSON 문자열 형태로 전달했는지 확인하세요.");
				throw new PaymentException("결제 API 응답의 customData 파싱 중 오류 발생");
			}

			log.info("추출한 merchantUid '{}'로 주문을 조회합니다...", merchantUidFromCustomData);
			Order order = orderRepository.findByMerchantUid(merchantUidFromCustomData)
				.orElseThrow(EntityNotFoundException::new);
			log.info("주문 조회 성공: orderId={}, currentStatus={}", order.getId(), order.getStatus());

			if (order.getStatus() == OrderStatus.PAID) {
				log.info("이미 처리된 주문: merchantUid={}", merchantUid);
				return;
			}

			// 2. 금액 검증
			if (!Objects.equals(paymentResponse.getAmount().getTotal(),
				order.getAmount().longValue())) {
				log.error("웹훅 금액 불일치: expected={}, actual={}",
					order.getAmount(), paymentResponse.getAmount().getTotal());
				order.setStatus(OrderStatus.FAILED);
				orderRepository.save(order);
				throw new PaymentException("결제 금액이 일치하지 않습니다.");
			}

			PaymentStatus paymentStatus = PaymentStatus.fromPortOneStatus(
				paymentResponse.getStatus());

			if (paymentStatus == PaymentStatus.PAID) {
				order.setStatus(OrderStatus.PAID);
				order.setPaymentId(paymentId);
				order.setPaidAt(LocalDateTime.now());

				Order savedOrder = orderRepository.save(order);
				log.info("웹훅으로 결제 완료 처리: orderId={}, paymentId={}",
					savedOrder.getId(), paymentId);

				// payment 저장
				Payment savedPayment = paymentRepository.save(
					Payment.create(
						paymentId,
						merchantUidFromCustomData,
						paymentResponse.getAmount().getTotal(),
						paymentStatus,
						savedOrder.getId(),
						savedOrder.getUserId(),
						savedOrder.getSubCategoryId()
					)
				);

				createInitialResult(savedPayment, savedOrder);

				processOrder(savedOrder);
			} else {
				log.error("웹훅 결제 실패: paymentId={}, status={}", paymentId, paymentStatus);
				order.setStatus(OrderStatus.FAILED);
				orderRepository.save(order);
				throw new PaymentException("결제 실패 상태입니다.");
			}
		} catch (Exception e) {
			log.error("웹훅 처리 중 에러", e);
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
		String rawJsonResponse = null; // 원시 JSON 저장 변수
		try {
			String url = "https://api.portone.io/payments/" + paymentId;

			// API 호출하여 원시 JSON 문자열 받기
			rawJsonResponse = restClient.get()
				.uri(url)
				.header(HttpHeaders.AUTHORIZATION, "PortOne " + portOneApiSecret)
				.accept(MediaType.APPLICATION_JSON)
				.retrieve()
				.body(String.class);

			log.info("PortOne API 원시 응답 (paymentId: {}): {}", paymentId, rawJsonResponse);

			if (rawJsonResponse == null || rawJsonResponse.isBlank()) {
				log.error("PortOne API로부터 비어있는 응답을 받았습니다. paymentId={}", paymentId);
				throw new PaymentException("PortOne API로부터 비어있는 응답을 받았습니다.");
			}

			PortOnePaymentResponse response = objectMapper.readValue(rawJsonResponse,
				PortOnePaymentResponse.class);

			if (response == null) {
				log.error("PortOne API 응답 JSON 파싱 실패. 원시 응답: {}", rawJsonResponse);
				throw new PaymentException("PortOne API 응답 파싱에 실패했습니다.");
			}

			return response;

		} catch (JsonProcessingException e) {
			log.error("PortOne API 응답 JSON 파싱 중 오류 발생. paymentId={}, 원시 응답: {}", paymentId,
				rawJsonResponse, e);
			throw new PaymentException("결제 정보 응답 처리 중 오류 발생 (JSON 파싱 실패)");
		} catch (Exception e) {
			log.error("PortOne API 호출 실패: paymentId={}", paymentId, e);
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

	private void createInitialResult(Payment savedPayment, Order savedOrder) {
		Long paymentPkId = savedPayment.getId(); // 상품의 PK 키 ..
		Long userId = savedOrder.getUserId();
		Long subCategoryId = savedOrder.getSubCategoryId();

		// SubCategory 정보 조회 (상품 이름 가져오기)
		SubCategory subCategory = subCategoryRepository.findById(subCategoryId)
			.orElseThrow(() -> {
				log.error("Payment 후 Result 생성 중 SubCategory 조회 실패: subCategoryId={}",
					subCategoryId);
				return new PaymentException("상품 정보를 찾을 수 없습니다: ID " + subCategoryId);
			});
		String productName = subCategory.getTitle();
		Long categoryId = subCategory.getCategoryId(); // categoryId 가져오기

		// Category ID에 따라 Result 또는 CompatibilityResult 생성 분기
		if (categoryId != null && (categoryId == 4 || categoryId == 6 || categoryId == 7)) {
			if (compatibilityResultRepository.findByPaymentId(paymentPkId).isEmpty()) {
				CompatibilityResult initialCompResult = CompatibilityResult.createInitial(userId,
					paymentPkId, productName);
				compatibilityResultRepository.save(initialCompResult);
				log.info(
					"초기 CompatibilityResult 생성 완료: paymentId(PK)={}, resultId={}, productName={}",
					paymentPkId, initialCompResult.getId(), productName);
			} else {
				log.warn(
					"이미 paymentId(PK) {}에 해당하는 CompatibilityResult가 존재하여 생성을 건너 뜁니다.",
					paymentPkId);
			}
		} else { // 그 외 모든 경우는 일반 Result 생성
			if (resultRepository.findByPaymentId(paymentPkId).isEmpty()) {
				Result initialResult = Result.createInitial(userId, paymentPkId, productName);
				resultRepository.save(initialResult);
				log.info("초기 Result 생성 완료: paymentId(PK)={}, resultId={}, productName={}",
					paymentPkId, initialResult.getId(), productName);
			} else {
				log.warn("이미 paymentId(PK) {}에 해당하는 Result가 존재하여 생성을 건너 뜁니다.",
					paymentPkId);
			}
		}
	}
}
