package com.mansereok.server.domain.notification.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@Slf4j
public class SlackNotificationService {

	@Value("${slack.webhook.url}")
	private String webhookUrl;

	private final RestTemplate restTemplate;

	/**
	 * 연결 3초·읽기 5초 타임아웃을 둔 RestTemplate 을 만든다. (DiscordNotificationService 와 같은 이유)
	 */
	public SlackNotificationService(RestTemplateBuilder restTemplateBuilder) {
		this.restTemplate = restTemplateBuilder
			.connectTimeout(Duration.ofSeconds(3))
			.readTimeout(Duration.ofSeconds(5))
			.build();
	}

	/**
	 * Slack으로 신규 회원 가입 알림을 전송합니다.
	 *
	 * @param userName  회원 이름
	 * @param email     회원 이메일
	 * @param userId    회원 ID
	 * @param joinType  가입 경로 (예: "일반 회원가입", "KAKAO OAuth")
	 * @param createdAt 가입 일시
	 */
	// 👈 3. 메서드 시그니처 수정 (파라미터 5개)
	public void sendUserCreatedNotification(
		String userName,
		String email,
		Long userId,
		String joinType,
		LocalDateTime createdAt
	) {
		try {
			Map<String, Object> message = new HashMap<>();
			message.put("channel", "91-namedsaju-회원가입-알림");
			message.put("username", "회원가입 알림 봇");
			message.put("icon_emoji", ":bust_in_silhouette:");
			message.put("text", "✅ *새로운 사용자 가입*");

			Map<String, Object> attachment = new HashMap<>();
			attachment.put("color", "good");
			
			attachment.put("text", String.format(
				"👤 이름: %s\n📧 이메일: %s\n🆔 ID: %d\n🚪 가입경로: %s\n⏰ 가입일시: %s",
				userName,
				email,
				userId,
				joinType,
				createdAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
			));

			message.put("attachments", new Object[]{attachment});

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);

			HttpEntity<Map<String, Object>> request = new HttpEntity<>(message, headers);

			restTemplate.postForEntity(webhookUrl, request, String.class);

			log.info("Slack 알림 전송 완료: userId={}", userId);

		} catch (Exception e) {
			log.error("Slack 알림 전송 실패", e);
		}
	}
}
