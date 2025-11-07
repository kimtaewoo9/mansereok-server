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
		LocalDateTime paidAt
	) {
		try {
			String formattedPaidAt =
				(paidAt != null) ? paidAt.format(dateTimeFormatter) : "시간 정보 없음";

			Map<String, Object> embed = new HashMap<>();
			embed.put("title", "💰 결제 완료");
			embed.put("color", 16766720); // 금색 (비슷하게)
			embed.put("description", String.format(
				"**상품명:** %s\n**결제 금액:** %,d원\n\n" +
					"**구매자:** %s (%s)\n" +
					"**결제 시간:** %s",
				productName, amount, userName, userEmail,
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

	// 단일 사주 해석
	public void sendInterpretationRequestNotification(
		String userName,
		String userEmail,
		String userBirthdate,
		Long subcategoryId
	) {
		try {
			Map<String, Object> embed = new HashMap<>();
			embed.put("title", "🔮 사주 해석 요청");
			embed.put("color", 10181046); // 보라색
			embed.put("description", String.format(
				"**카테고리 ID:** %d\n\n" +
					"**요청자:** %s\n" +
					"**이메일:** %s\n" +
					"**생년월일:** %s",
				subcategoryId,
				userName,
				userEmail,
				userBirthdate
			));

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

			log.info("Discord 사주 요청 알림 전송 완료: userName={}", userName);

		} catch (Exception e) {
			log.error("Discord 사주 요청 알림 전송 실패", e);
		}
	}

	public void sendCompatibilityRequestNotification(
		String person1Name,
		String person1Birthdate,
		String person2Name,
		String person2Birthdate
	) {
		try {
			Map<String, Object> embed = new HashMap<>();
			embed.put("title", "💕 궁합 해석 요청");
			embed.put("color", 15277667); // 핑크색
			embed.put("description", String.format(
				"**대상자1:** %s (%s)\n" +
					"**대상자2:** %s (%s)",
				person1Name, person1Birthdate,
				person2Name, person2Birthdate
			));

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

			log.info("Discord 궁합 요청 알림 전송 완료: {} & {}", person1Name, person2Name);

		} catch (Exception e) {
			log.error("Discord 궁합 요청 알림 전송 실패", e);
		}
	}
}
