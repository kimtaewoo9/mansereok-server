package com.mansereok.server.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SesException;

/**
 * 메일마다 SES 로 보내는 요청(받는 주소, 발신 주소, 제목, 본문)이 맞는지, 전송에 실패해도 예외를 던지지 않고 받는 주소를 가린 로그만
 * 남기는지 확인한다.
 *
 * <p>SES 는 바깥 시스템이라 SesClient 를 목으로 바꾸고, 보낸 요청은 ArgumentCaptor 로 잡아 본다. {@code @Async} 메서드도
 * 스프링 없이 직접 부르면 같은 스레드에서 돈다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class EmailServiceTest {

	private static final String FROM_EMAIL = "welcome@namedsaju.com";
	private static final String FROM_NAME = "네임드사주";
	private static final String FRONTEND_URL = "https://www.namedsaju.com";

	@Mock
	private SesClient sesClient;

	private EmailService emailService;

	@BeforeEach
	void setUp() {
		emailService = new EmailService(sesClient, FROM_EMAIL, FROM_NAME, FRONTEND_URL);
	}

	@Nested
	@DisplayName("비밀번호 재설정 메일을 보내면")
	class PasswordResetMail {

		@ParameterizedTest(name = "[{index}] 프론트엔드 주소 {0}")
		@ValueSource(strings = {"https://www.namedsaju.com", "https://www.namedsaju.com/"})
		@DisplayName("프론트엔드 주소가 '/' 로 끝나든 아니든 본문에 같은 재설정 링크를 넣는다")
		void putsResetLinkWithoutDoubleSlash(String frontendUrl) {
			// given
			EmailService service = new EmailService(sesClient, FROM_EMAIL, FROM_NAME, frontendUrl);

			// when
			service.sendPasswordResetEmail("user@example.com", "tok-1");

			// then
			assertThat(htmlBodyOf(sentRequest()))
				.contains("href=\"https://www.namedsaju.com/auth/reset-password?token=tok-1\"")
				.doesNotContain("//auth");
		}

		@Test
		@DisplayName("설정의 발신 이름과 주소로, 받는 주소 한 곳에, UTF-8 제목과 본문을 담아 보낸다")
		void buildsRequestFromSettings() {
			// when
			emailService.sendPasswordResetEmail("user@example.com", "tok-1");

			// then
			SendEmailRequest request = sentRequest();
			assertThat(request.source()).isEqualTo("네임드사주 <welcome@namedsaju.com>");
			assertThat(request.destination().toAddresses()).containsExactly("user@example.com");
			assertThat(request.message().subject().data()).isEqualTo("[NAMED] 비밀번호 재설정 안내");
			assertThat(request.message().subject().charset()).isEqualTo("UTF-8");
			assertThat(request.message().body().html().charset()).isEqualTo("UTF-8");
		}
	}

	@Nested
	@DisplayName("소셜 로그인 안내 메일을 보내면")
	class SocialLoginGuideMail {

		@Test
		@DisplayName("이름에 HTML 이 들어 있으면 태그로 해석되지 않게 이스케이프해서 넣는다")
		void escapesHtmlInName() {
			// when
			emailService.sendSocialLoginGuideEmail("user@example.com", "<b>x</b>");

			// then
			assertThat(htmlBodyOf(sentRequest()))
				.contains("안녕하세요, &lt;b&gt;x&lt;/b&gt;님.")
				.doesNotContain("<b>x</b>");
		}

		@Test
		@DisplayName("이름이 없으면 '회원' 으로 부른다")
		void callsUserMemberWhenNameIsMissing() {
			// when
			emailService.sendSocialLoginGuideEmail("user@example.com", null);

			// then
			assertThat(htmlBodyOf(sentRequest())).contains("안녕하세요, 회원님.");
		}
	}

	@Nested
	@DisplayName("해석 결과 준비 메일을 보내면")
	class ResultReadyMail {

		@ParameterizedTest(name = "[{index}] 프론트엔드 주소 {0}")
		@ValueSource(strings = {"https://www.namedsaju.com", "https://www.namedsaju.com/"})
		@DisplayName("프론트엔드 주소가 '/' 로 끝나든 아니든 본문에 같은 마이페이지 링크를 넣는다")
		void putsMypageLinkWithoutDoubleSlash(String frontendUrl) {
			// given
			EmailService service = new EmailService(sesClient, FROM_EMAIL, FROM_NAME, frontendUrl);

			// when
			service.sendResultReadyEmail("user@example.com", "홍길동");

			// then
			assertThat(htmlBodyOf(sentRequest()))
				.contains("href=\"https://www.namedsaju.com/mypage/fortunes\"");
		}
	}

	@ParameterizedTest(name = "[{index}] {0}")
	@MethodSource("everyMail")
	@DisplayName("어느 메일이든 본문에 서식 문자열용 '%%' 가 남지 않고 '%' 로 바뀌어 있다")
	void leavesNoDoublePercentInBody(String mailName, Consumer<EmailService> sendMail) {
		// when
		sendMail.accept(emailService);

		// then
		assertThat(htmlBodyOf(sentRequest()))
			.contains("width=\"100%\"")
			.doesNotContain("%%");
	}

	static Stream<Arguments> everyMail() {
		return Stream.of(
			Arguments.of("해석 결과 준비",
				(Consumer<EmailService>)service -> service.sendResultReadyEmail("user@example.com", "홍길동")),
			Arguments.of("비밀번호 재설정",
				(Consumer<EmailService>)service -> service.sendPasswordResetEmail("user@example.com", "tok-1")),
			Arguments.of("소셜 로그인 안내",
				(Consumer<EmailService>)service -> service.sendSocialLoginGuideEmail("user@example.com", "홍길동")));
	}

	@Nested
	@DisplayName("전송에 실패하면")
	class WhenSendingFails {

		@Test
		@DisplayName("SES 가 거절해도 예외를 밖으로 던지지 않고, 로그에는 가린 받는 주소와 오류 코드만 남긴다")
		void swallowsSesRejectionAndLogsMaskedAddress(CapturedOutput output) {
			// given: SES 샌드박스의 거절 메시지처럼 오류 메시지에 받는 주소가 그대로 들어 있다
			String rejectedMessage = "Email address is not verified. The following identities failed the check in "
				+ "region AP-NORTHEAST-2: user@example.com";
			given(sesClient.sendEmail(any(SendEmailRequest.class))).willThrow(SesException.builder()
				.message(rejectedMessage)
				.statusCode(400)
				.requestId("request-1")
				.awsErrorDetails(AwsErrorDetails.builder()
					.errorCode("MessageRejected")
					.errorMessage(rejectedMessage)
					.serviceName("Ses")
					.build())
				.build());

			// when & then
			assertThatCode(() -> emailService.sendPasswordResetEmail("user@example.com", "tok-1"))
				.doesNotThrowAnyException();
			assertThat(output.getAll())
				.contains("메일 전송 실패", "u***@example.com", "MessageRejected", "request-1")
				.doesNotContain("user@example.com", "tok-1");
		}

		@Test
		@DisplayName("SES 에 닿지 못해도 예외를 밖으로 던지지 않고, 로그에는 가린 받는 주소를 남긴다")
		void swallowsClientFailureAndLogsMaskedAddress(CapturedOutput output) {
			// given
			given(sesClient.sendEmail(any(SendEmailRequest.class)))
				.willThrow(SdkClientException.create("Unable to execute HTTP request"));

			// when & then
			assertThatCode(() -> emailService.sendSocialLoginGuideEmail("user@example.com", "홍길동"))
				.doesNotThrowAnyException();
			assertThat(output.getAll())
				.contains("메일 전송 중 알 수 없는 오류", "u***@example.com")
				.doesNotContain("user@example.com");
		}
	}

	private SendEmailRequest sentRequest() {
		ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
		then(sesClient).should().sendEmail(captor.capture());
		return captor.getValue();
	}

	private static String htmlBodyOf(SendEmailRequest request) {
		return request.message().body().html().data();
	}
}
