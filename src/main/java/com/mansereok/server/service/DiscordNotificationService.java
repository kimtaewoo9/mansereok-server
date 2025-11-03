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
public class DiscordNotificationService {

	@Value("${discord.webhook.url}")
	private String webhookUrl;

	private final RestTemplate restTemplate = new RestTemplate();

	public void sendUserCreatedNotification(String userName, String email, Long userId,
		String signupType) {
		try {
			Map<String, Object> embed = new HashMap<>();
			embed.put("title", "✅ 새로운 사용자 가입");
			embed.put("color", 3066993);  // 초록색
			embed.put("description", String.format(
				"**이름:** %s\n **이메일:** %s\n **ID:** %d\n **가입 유형:** %s",
				userName, email, userId, signupType
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

			restTemplate.postForEntity(webhookUrl, request, String.class);

			log.info("Discord 알림 전송 완료: userId={}, signupType={}", userId, signupType);

		} catch (Exception e) {
			log.error("Discord 알림 전송 실패", e);
		}
	}
}
