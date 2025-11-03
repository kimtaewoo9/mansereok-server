package com.mansereok.server.service;

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
public class SlackNotificationService {

	@Value("${slack.webhook.url}")
	private String webhookUrl;

	private final RestTemplate restTemplate = new RestTemplate();

	public void sendUserCreatedNotification(String userName, String email, Long userId) {
		try {
			Map<String, Object> message = new HashMap<>();
			message.put("channel", "#31-project-namedsaju");
			message.put("username", "User Service Bot");
			message.put("icon_emoji", ":bust_in_silhouette:");
			message.put("text", "✅ *새로운 사용자 가입*");

			Map<String, Object> attachment = new HashMap<>();
			attachment.put("color", "good");
			attachment.put("text", String.format(
				"👤 이름: %s\n📧 이메일: %s\n🆔 ID: %d",
				userName, email, userId
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
