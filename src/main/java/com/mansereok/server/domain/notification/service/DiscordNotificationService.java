package com.mansereok.server.domain.notification.service;

import com.mansereok.server.domain.payment.reconciliation.entity.PaymentReconciliationMismatch;
import com.mansereok.server.domain.payment.reconciliation.entity.PaymentReconciliationRun;
import com.mansereok.server.domain.payment.reconciliation.entity.ReconciliationStatus;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
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
public class DiscordNotificationService {

	@Value("${discord.webhook.signup-url}")
	private String signupWebhookUrl;

	@Value("${discord.webhook.signup-url-channel2}")
	private String signupWebhookUrl2;

	@Value("${discord.webhook.payment-url}")
	private String paymentWebhookUrl;

	@Value("${discord.webhook.interpretation-request-url}")
	private String interpretationRequestWebhookUrl;

	private final RestTemplate restTemplate;

	private static final DateTimeFormatter dateTimeFormatter =
		DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	/** 대사 보고에 한 줄씩 적을 불일치 최대 건수. */
	private static final int DISCORD_MAX_LINES = 10;

	/**
	 * 연결 3초·읽기 5초 타임아웃을 둔 RestTemplate 을 만든다. 예전의 {@code new RestTemplate()} 은 타임아웃이
	 * 없어 Discord 가 응답하지 않으면 호출 스레드가 무한 대기했다.
	 */
	public DiscordNotificationService(RestTemplateBuilder restTemplateBuilder) {
		this.restTemplate = restTemplateBuilder
			.connectTimeout(Duration.ofSeconds(3))
			.readTimeout(Duration.ofSeconds(5))
			.build();
	}

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

			log.info("Discord 회원 탈퇴 알림 전송 완료: userName={}", userName);

		} catch (Exception e) {
			log.error("Discord 회원 탈퇴 알림 전송 실패", e);
		}
	}

	/**
	 * 일 배치 결제 대사 결과 보고. 불일치가 있거나 대사가 실패했을 때만 호출된다.
	 *
	 * <p>불일치 목록이 길어도 채널이 읽기 어려워지지 않도록 {@value #DISCORD_MAX_LINES} 건까지만 적고 나머지는
	 * 건수로 줄인다. 자세한 내용은 payment_reconciliation_mismatches 테이블에 남는다. 고객 개인정보는 담지 않는다.
	 */
	public void sendPaymentReconciliationReport(PaymentReconciliationRun run,
		List<PaymentReconciliationMismatch> mismatches) {
		try {
			Map<String, Object> embed = new HashMap<>();
			embed.put("title", reconciliationTitle(run));
			embed.put("color", needsAttention(run) ? 15158332 : 9807270); // 빨간색 / 회색
			embed.put("description", reconciliationDescription(run, mismatches));

			Map<String, Object> footer = new HashMap<>();
			footer.put("text", "만세력 서비스");
			embed.put("footer", footer);

			Map<String, Object> message = new HashMap<>();
			message.put("username", "결제 대사 Bot");
			message.put("embeds", new Object[]{embed});

			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);

			HttpEntity<Map<String, Object>> request = new HttpEntity<>(message, headers);

			restTemplate.postForEntity(paymentWebhookUrl, request, String.class);

			log.info("Discord 결제 대사 알림 전송 완료: targetDate={}, status={}, mismatchCount={}",
				run.getTargetDate(), run.getStatus(), mismatches.size());

		} catch (Exception e) {
			log.error("Discord 결제 대사 알림 전송 실패", e);
		}
	}

	private boolean needsAttention(PaymentReconciliationRun run) {
		return run.getStatus() == ReconciliationStatus.FAILED || run.getMismatchCount() > 0;
	}

	private String reconciliationTitle(PaymentReconciliationRun run) {
		if (run.getStatus() == ReconciliationStatus.FAILED) {
			return String.format("🚨 결제 대사 실패 (%s)", run.getTargetDate());
		}
		return String.format("🧾 결제 대사 결과 (%s)", run.getTargetDate());
	}

	private String reconciliationDescription(PaymentReconciliationRun run,
		List<PaymentReconciliationMismatch> mismatches) {
		if (run.getStatus() == ReconciliationStatus.FAILED) {
			return String.format("**오류:** %s", run.getErrorMessage());
		}

		StringBuilder description = new StringBuilder(String.format(
			"**PG 건수:** %d건\n**DB 건수:** %d건\n**불일치:** %d건",
			run.getPgPaymentCount(), run.getDbPaymentCount(), run.getMismatchCount()
		));

		if (!mismatches.isEmpty()) {
			description.append("\n");
		}
		int lines = Math.min(mismatches.size(), DISCORD_MAX_LINES);
		for (int i = 0; i < lines; i++) {
			description.append("\n").append(mismatchLine(mismatches.get(i)));
		}
		if (mismatches.size() > DISCORD_MAX_LINES) {
			description.append(String.format("\n외 %d건", mismatches.size() - DISCORD_MAX_LINES));
		}
		return description.toString();
	}

	private String mismatchLine(PaymentReconciliationMismatch mismatch) {
		return String.format("`%s` %s pg=%s/%s db=%s/%s",
			mismatch.getType(), mismatch.getImpUid(),
			orDash(mismatch.getPgStatus()), orDash(mismatch.getPgAmount()),
			orDash(mismatch.getDbStatus()), orDash(mismatch.getDbAmount()));
	}

	private String orDash(Object value) {
		return value == null ? "-" : value.toString();
	}
}
