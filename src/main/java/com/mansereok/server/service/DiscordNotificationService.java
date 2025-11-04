package com.mansereok.server.service;

import java.time.LocalDate;
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

	@Value("${discord.webhook.payment-url}")
	private String paymentWebhookUrl;

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

			restTemplate.postForEntity(signupWebhookUrl, request, String.class);

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
		LocalDate birthDate
	) {
		try {
			String formattedPaidAt =
				(paidAt != null) ? paidAt.format(dateTimeFormatter) : "시간 정보 없음";

			String formattedBirthDate = (birthDate != null)
				? birthDate.format(DateTimeFormatter.ofPattern("yyyy년 MM월 dd일"))
				: "미입력";

			Map<String, Object> embed = new HashMap<>();
			embed.put("title", "💰 결제 완료");
			embed.put("color", 16766720); // 금색 (비슷하게)
			embed.put("description", String.format(
				"**상품명:** %s\n**결제 금액:** %,d원\n\n" +
					"**구매자:** %s (%s)\n" +
					"**생년월일:** %s\n" +                    // ← 추가
					"**결제 시간:** %s",
				productName, amount, userName, userEmail,
				formattedBirthDate,           // ← 추가
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
}
