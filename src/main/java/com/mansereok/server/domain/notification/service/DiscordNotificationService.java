package com.mansereok.server.domain.notification.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class DiscordNotificationService {

	@Value("${discord.webhook.signup-url}")
	private String signupWebhookUrl;

	@Value("${discord.webhook.signup-url-channel2}")
	private String signupWebhookUrl2;

	@Value("${discord.webhook.payment-url}")
	private String paymentWebhookUrl;

	@Value("${discord.webhook.interpretation-request-url}")
	private String interpretationRequestWebhookUrl;

	private final RestTemplate restTemplate = new RestTemplate();

	private static final DateTimeFormatter dateTimeFormatter =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	public void sendUserCreatedNotification(String userName, String email, Long userId,
		String signupType, LocalDateTime createdAt) {
		try {
			String formattedCreatedAt =
				(createdAt != null) ? createdAt.format(dateTimeFormatter) : "시간 정보 없음";

			Map<String, Object> embed = new HashMap<>();
			embed.put("title", "✅ 새로운 사용자 가입");
			embed.put("color", 3066993);  // 초록색
			embed.put("description", String.format(
				"**이름:** %s\n**이메일:** %s\n**ID:** %d\n**가입 유형:** %s\n**가입 시간:** %s",
				userName, email, userId, signupType, formattedCreatedAt
			));

			Map<String, Object> footer = new HashMap<>();
			footer.put("text", "만세력 서비스");
			embed.put("footer", footer);

			Map<String, Object> message = new HashMap<>();
			message.put("username", "회원가입 Bot");
			message.put("embeds", new Object[]{embed});

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);

			HttpEntity<Map<String, Object>> request = new HttpEntity<>(message, headers);

			if (signupWebhookUrl != null && !signupWebhookUrl.isBlank()) {
				restTemplate.postForEntity(signupWebhookUrl, request, String.class);
				log.info("Discord 회원가입 알림 (채널1) 전송 완료: userId={}, signupType={}", userId,
					signupType);
			}

			// 두 번째 채널로 전송
			if (signupWebhookUrl2 != null && !signupWebhookUrl2.isBlank()) {
				restTemplate.postForEntity(signupWebhookUrl2, request, String.class);
				log.info("Discord 회원가입 알림 (채널2) 전송 완료: userId={}, signupType={}", userId,
					signupType);
			}

			log.info("Discord 회원가입 알림 전송 완료: userId={}, signupType={}", userId, signupType);

		} catch (Exception e) {
			log.error("Discord 회원가입 알림 전송 실패", e);
		}
	}

	public void sendPaymentCompletedNotification(
		String userName,
		String userEmail,
		Long amount,
		String productName,
		LocalDateTime paidAt,
		String discountCode,
		Integer originalAmount
	) {
		try {
			String formattedPaidAt =
				(paidAt != null) ? paidAt.format(dateTimeFormatter) : "시간 정보 없음";

			// 할인 정보 추가
			String priceInfo;
			if (discountCode != null && !discountCode.isEmpty() && originalAmount != null) {
				int discountAmount = originalAmount - amount.intValue();
				double discountRate = (discountAmount / (double) originalAmount) * 100;

				priceInfo = String.format(
					"**할인 코드:** `%s`\n**원가:** %,d원\n**할인가:** %,d원 (%.0f%% 할인)",
					discountCode, originalAmount, amount, discountRate
				);
			} else {
				priceInfo = String.format("**결제 금액:** %,d원", amount);
			}

			Map<String, Object> embed = new HashMap<>();
			embed.put("title", "💰 결제 완료");
			embed.put("color", 16766720); // 금색
			embed.put("description", String.format(
				"**상품명:** %s\n%s\n\n" +
					"**구매자:** %s (%s)\n" +
					"**결제 시간:** %s",
				productName, priceInfo, userName, userEmail,
				formattedPaidAt
			));

			Map<String, Object> footer = new HashMap<>();
			footer.put("text", "만세력 서비스");
			embed.put("footer", footer);

			Map<String, Object> message = new HashMap<>();
			message.put("username", "결제 Bot");
			message.put("embeds", new Object[]{embed});

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);

			HttpEntity<Map<String, Object>> request = new HttpEntity<>(message, headers);

			restTemplate.postForEntity(paymentWebhookUrl, request, String.class);

			log.info("Discord 결제 알림 전송 완료: productName={}, amount={}", productName, amount);

		} catch (Exception e) {
			log.error("Discord 결제 알림 전송 실패", e);
		}
	}

	/**
	 * 단일 사주 해석 요청 알림. 결제 ID 와 상품, 유료·무료 구분만 보낸다. 요청자 이름·이메일·생년월일은 외부 채널에 남기지 않는다.
	 * 누구의 요청인지는 결제 ID 로 DB 에서 찾는다.
	 */
	public void sendInterpretationRequestNotification(Long paymentId, String productName, boolean free) {
		try {
			Map<String, Object> embed = new HashMap<>();
			embed.put("title", "🔮 사주 해석 요청");
			embed.put("color", 10181046); // 보라색
			embed.put("description", interpretationRequestDescription(paymentId, productName, free));

			Map<String, Object> footer = new HashMap<>();
			footer.put("text", "만세력 서비스");
			embed.put("footer", footer);

			Map<String, Object> message = new HashMap<>();
			message.put("username", "사주봇");
			message.put("embeds", new Object[]{embed});

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			HttpEntity<Map<String, Object>> request = new HttpEntity<>(message, headers);

			restTemplate.postForEntity(interpretationRequestWebhookUrl, request, String.class);

			log.info("Discord 사주 요청 알림 전송 완료: paymentId={}", paymentId);

		} catch (Exception e) {
			log.error("Discord 사주 요청 알림 전송 실패", e);
		}
	}

	/**
	 * 궁합 해석 요청 알림. 결제 ID 와 상품, 유료·무료 구분만 보낸다. 두 사람의 이름과 생년월일은 보내지 않는다. 궁합 상대는 서비스에
	 * 가입해 동의한 사람이 아니다.
	 */
	public void sendCompatibilityRequestNotification(Long paymentId, String productName, boolean free) {
		try {
			Map<String, Object> embed = new HashMap<>();
			embed.put("title", "💕 궁합 해석 요청");
			embed.put("color", 15277667); // 핑크색
			embed.put("description", interpretationRequestDescription(paymentId, productName, free));

			Map<String, Object> footer = new HashMap<>();
			footer.put("text", "만세력 서비스");
			embed.put("footer", footer);

			Map<String, Object> message = new HashMap<>();
			message.put("username", "사주봇");
			message.put("embeds", new Object[]{embed});

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			HttpEntity<Map<String, Object>> request = new HttpEntity<>(message, headers);

			restTemplate.postForEntity(interpretationRequestWebhookUrl, request, String.class);

			log.info("Discord 궁합 요청 알림 전송 완료: paymentId={}", paymentId);

		} catch (Exception e) {
			log.error("Discord 궁합 요청 알림 전송 실패", e);
		}
	}

	private static String interpretationRequestDescription(Long paymentId, String productName, boolean free) {
		return String.format(
			"**결제 ID:** %d\n" +
				"**상품:** %s\n" +
				"**구분:** %s",
			paymentId,
			productName,
			free ? "무료" : "유료"
		);
	}

	public void sendUserWithdrawnNotification(String userName, String email) {
		try {
			Map<String, Object> embed = new HashMap<>();
			embed.put("title", "⛔ 회원 탈퇴");
			embed.put("color", 15158332);  // 빨간색
			embed.put("description", String.format(
				"**이름:** %s\n**이메일:** %s\n**탈퇴 시간:** %s",
				userName, email, LocalDateTime.now().format(dateTimeFormatter)
			));

			Map<String, Object> footer = new HashMap<>();
			footer.put("text", "만세력 서비스");
			embed.put("footer", footer);

			Map<String, Object> message = new HashMap<>();
			message.put("username", "회원관리 Bot");
			message.put("embeds", new Object[]{embed});

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);

			HttpEntity<Map<String, Object>> request = new HttpEntity<>(message, headers);

			// 회원가입 알림 웹훅 URL을 재사용 (필요 시 별도 URL 분리 가능)
			if (signupWebhookUrl != null && !signupWebhookUrl.isBlank()) {
				restTemplate.postForEntity(signupWebhookUrl, request, String.class);
			}

			// 두 번째 채널에도 전송
			if (signupWebhookUrl2 != null && !signupWebhookUrl2.isBlank()) {
				restTemplate.postForEntity(signupWebhookUrl2, request, String.class);
			}

			log.info("Discord 회원 탈퇴 알림 전송 완료: email={}", email);

		} catch (Exception e) {
			log.error("Discord 회원 탈퇴 알림 전송 실패", e);
		}
	}
}
