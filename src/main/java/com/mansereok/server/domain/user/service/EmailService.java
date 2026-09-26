package com.mansereok.server.domain.user.service;

import com.mansereok.server.global.util.PersonalInfoMasker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SesException;

/**
 * 회원에게 가는 메일(해석 결과 준비, 비밀번호 재설정, 소셜 로그인 안내)을 SES 로 보낸다.
 *
 * <p>공개 메서드는 제목과 HTML 본문만 만들고, SES 요청 조립과 전송, 실패 로그는 {@link #send} 한 곳에서 한다. 발신 주소나 문자
 * 인코딩을 바꿀 때 한 곳만 고치면 된다.
 *
 * <p>메일은 부가 기능이라 전송에 실패해도 예외를 밖으로 던지지 않는다. 로그에는 받는 주소를 가려서 남긴다.
 */
@Service
@Slf4j
public class EmailService {

	private static final String CHARSET = "UTF-8";

	private final SesClient sesClient;
	private final String fromAddress;
	private final String frontendUrl;

	public EmailService(
		SesClient sesClient,
		@Value("${aws.ses.from-email:help@namedsaju.com}") String fromEmail,
		@Value("${aws.ses.from-name:네임드사주}") String fromName,
		@Value("${app.frontend-url}") String frontendUrl) {
		this.sesClient = sesClient;
		this.fromAddress = fromName + " <" + fromEmail + ">";
		this.frontendUrl = frontendUrl;
	}

	/**
	 * 사주 해석 결과가 준비됐다고 알리고 마이페이지의 결과 목록 링크를 보낸다. userName 은 본문에 쓰지 않는다.
	 */
	public void sendResultReadyEmail(String toEmail, String userName) {
		String subject = "NAMED 사주 리포트 완성! 지금 이야기를 확인해 보세요.";
		String mypageUrl = UriComponentsBuilder.fromUriString(frontendUrl)
			.path("/mypage/fortunes")
			.toUriString();
		send(toEmail, subject, createResultReadyEmailHtml(mypageUrl));
	}

	/**
	 * 프론트엔드의 비밀번호 변경 페이지로 가는 재설정 링크를 보낸다. 설정의 프론트엔드 주소가 '/' 로 끝나도 링크에 '//' 가 생기지 않는다.
	 */
	@Async
	public void sendPasswordResetEmail(String toEmail, String token) {
		String subject = "[NAMED] 비밀번호 재설정 안내";
		String resetLink = UriComponentsBuilder.fromUriString(frontendUrl)
			.path("/auth/reset-password")
			.queryParam("token", token)
			.toUriString();
		send(toEmail, subject, createPasswordResetEmailHtml(resetLink));
	}

	/**
	 * 소셜 로그인으로 가입한 회원이 비밀번호 재설정을 요청하면, 비밀번호 대신 소셜 로그인을 쓰라고 안내한다.
	 *
	 * <p>이름은 회원이 프로필에서 마음대로 바꿀 수 있으므로 HTML 로 해석되지 않게 이스케이프해서 넣는다.
	 */
	@Async
	public void sendSocialLoginGuideEmail(String toEmail, String name) {
		String subject = "[NAMED] 비밀번호 재설정 안내";
		String displayName = name != null ? HtmlUtils.htmlEscape(name) : "회원";
		send(toEmail, subject, createSocialLoginGuideEmailHtml(displayName));
	}

	/**
	 * 받는 주소, 제목, HTML 본문으로 SES 요청을 만들어 보낸다. 발신 주소와 문자 인코딩은 모든 메일이 같다.
	 *
	 * <p>실패해도 예외를 던지지 않고 로그만 남긴다. SES 오류 메시지에는 확인되지 않은 받는 주소가 그대로 들어가기도 해서, SES 가
	 * 거절한 경우에는 메시지 대신 상태 코드와 오류 코드만 남긴다.
	 */
	private void send(String toEmail, String subject, String htmlBody) {
		String maskedEmail = PersonalInfoMasker.maskEmail(toEmail);
		try {
			SendEmailRequest request = SendEmailRequest.builder()
				.destination(Destination.builder().toAddresses(toEmail).build())
				.message(Message.builder()
					.subject(utf8(subject))
					.body(Body.builder().html(utf8(htmlBody)).build())
					.build())
				.source(fromAddress)
				.build();
			sesClient.sendEmail(request);
			log.info("메일 전송 완료: {} (받는 주소: {})", subject, maskedEmail);
		} catch (SesException e) {
			log.error("메일 전송 실패: {} (받는 주소: {}, 상태 코드: {}, 오류 코드: {}, 요청 id: {})", subject, maskedEmail,
				e.statusCode(), e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null, e.requestId());
		} catch (Exception e) {
			log.error("메일 전송 중 알 수 없는 오류: {} (받는 주소: {})", subject, maskedEmail, e);
		}
	}

	private static Content utf8(String data) {
		return Content.builder().charset(CHARSET).data(data).build();
	}

	private String createResultReadyEmailHtml(String mypageUrl) {

		return """
			<!DOCTYPE html>
			<html lang="ko">
			<head>
			    <meta charset="UTF-8">
			    <meta name="viewport" content="width=device-width, initial-scale=1.0">
			    <meta name="color-scheme" content="light only">
			    <meta name="supported-color-schemes" content="light">
			    <title>NAMED 사주 리포트 완성</title>
			    <style type="text/css">
			        :root { color-scheme: light only; supported-color-schemes: light; }
			        * { color-scheme: light only !important; }
			        img { -webkit-filter: none !important; filter: none !important; }
			        body, table, td { background-color: #ffffff !important; }
			        .email-container { background-color: #ffffff !important; }
			        @media (prefers-color-scheme: dark) {
			            body, table, td, .email-container { background-color: #ffffff !important; color: #000000 !important; }
			            img { opacity: 1 !important; }
			        }
			    </style>
			</head>
			<body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif; background-color: #ffffff !important;">
			
			    <div style="display: none; max-height: 0px; overflow: hidden;">
			        회원님의 사주 리포트가 완성되었어요.
			    </div>
			
			    <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="100%%" style="background-color: #ffffff !important;" class="email-container">
			        <tr>
			            <td align="center" style="padding: 40px 20px; background-color: #ffffff !important;">
			
			                <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="700" style="max-width: 700px; background-image: url('https://named-logo.s3.ap-northeast-2.amazonaws.com/email-gradient-border.png'); background-size: cover; background-position: center; background-repeat: no-repeat; background-color: #00D4FF;">
			                    <tr>
			                        <td style="padding: 15px;">
			
			                            <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="100%%" style="background-color: #ffffff !important;">
			                                <tr>
			                                    <td style="padding: 60px 50px; text-align: center; background-color: #ffffff !important;">
			
			                                        <div style="background-color: #ffffff !important; padding: 10px 0;">
			                                            <img src="https://named-logo.s3.ap-northeast-2.amazonaws.com/named-logo.png" 
			                                                 alt="NAMED Logo" 
			                                                 width="80" 
			                                                 height="80" 
			                                                 style="display: block; margin: 0 auto 25px; border: 0; max-width: 80px; height: auto;">
			                                            <h1 style="margin: 0 0 50px 0; font-size: 40px; font-weight: 400; color: #000000 !important; letter-spacing: -1px; line-height: 1.2;">
			                                                <span style="font-weight: 700; color: #000000 !important;">NAMED</span>
			                                            </h1>
			                                        </div>
			
			                                        <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="100%%" style="background-color: #F8F8F8 !important; margin: 0 0 40px 0;">
			                                            <tr>
			                                                <td style="padding: 40px 35px; background-color: #F8F8F8 !important;">
			                                                    <p style="margin: 0 0 20px 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        안녕하세요. NAMED입니다.
			                                                    </p>
			                                                    <p style="margin: 0 0 10px 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        진짜 '나'를 찾는 여정, 그 두 번째 문이 열렸습니다.
			                                                    </p>
			                                                    <p style="margin: 0 0 40px 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        회원님을 위한 사주 리포트가 완성되었어요.
			                                                    </p>
			                                                    <p style="margin: 0 0 40px 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        지금 바로 확인해보세요.
			                                                    </p>
			
			                                                    <p style="margin: 0 0 40px 0; text-align: center;">
			                                                        <a href="%s" target="_blank" style="font-size: 18px; font-weight: 700; color: #444444; text-decoration: none; border: 2px solid #DDDDDD; padding: 12px 25px; border-radius: 8px; display: inline-block;">
			                                                            리포트 확인하기
			                                                        </a>
			                                                    </p>
			
			                                                    <p style="margin: 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        오늘의 리포트가 작은 힌트가 되길 바랍니다.
			                                                    </p>
			                                                </td>
			                                            </tr>
			                                        </table>
			
			                                        <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="100%%" style="border-top: 1px solid #E5E5E5; padding-top: 35px; background-color: #ffffff !important;">
			                                            <tr>
			                                                <td align="center" style="background-color: #ffffff !important;">
			                                                    <p style="margin: 0 0 6px 0; font-size: 13px; line-height: 1.5; color: #999999 !important;">네임드사주 NAMED</p>
			                                                    <p style="margin: 0 0 15px 0; font-size: 13px; line-height: 1.5; color: #999999 !important;">문의: help@namedsaju.com</p>
			
			                                                    <p style="margin: 0; font-size: 12px; line-height: 1.5; color: #CCCCCC !important;">
			                                                        본 메일은 정보통신망법에 의거하여<br>
			                                                        서비스 이용에 필수적인 정보성 메일로,<br>
			                                                        수신 동의 여부와 관계없이 발송됩니다.
			                                                    </p>
			                                                </td>
			                                            </tr>
			                                        </table>
			
			                                    </td>
			                                </tr>
			                            </table>
			
			                        </td>
			                    </tr>
			                </table>
			
			            </td>
			        </tr>
			    </table>
			
			</body>
			</html>
			""".formatted(mypageUrl);
	}

	private String createPasswordResetEmailHtml(String resetLink) {
		return """
			<!DOCTYPE html>
			<html lang="ko">
			<head>
			    <meta charset="UTF-8">
			    <meta name="viewport" content="width=device-width, initial-scale=1.0">
			    <title>NAMED 비밀번호 재설정</title>
			    <style type="text/css">
			        :root { color-scheme: light only; supported-color-schemes: light; }
			        * { color-scheme: light only !important; }
			        body, table, td { background-color: #ffffff !important; }
			        .email-container { background-color: #ffffff !important; }
			    </style>
			</head>
			<body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif; background-color: #ffffff !important;">
			
			    <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="100%%" style="background-color: #ffffff !important;" class="email-container">
			        <tr>
			            <td align="center" style="padding: 40px 20px;">
			
			                <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="600" style="max-width: 600px; background-color: #ffffff !important;">
			                    <tr>
			                        <td style="padding: 40px 0; text-align: center;">
			
			                            <h1 style="margin: 0 0 30px 0; font-size: 28px; font-weight: 700; color: #000000 !important; letter-spacing: -0.5px;">
			                                비밀번호 재설정
			                            </h1>
			
			                            <p style="margin: 0 0 10px 0; font-size: 16px; line-height: 1.6; color: #333333 !important;">
			                                안녕하세요. NAMED입니다.
			                            </p>
			                            <p style="margin: 0 0 40px 0; font-size: 16px; line-height: 1.6; color: #555555 !important;">
			                                비밀번호 재설정 요청을 확인했습니다.<br>
			                                아래 버튼을 클릭하여 새로운 비밀번호를 설정해주세요.
			                            </p>
			
			                            <a href="%s" target="_blank" style="display: inline-block; padding: 16px 40px; background-color: #000000; color: #ffffff !important; text-decoration: none; border-radius: 6px; font-size: 16px; font-weight: 700; letter-spacing: -0.5px;">
			                                비밀번호 변경하기
			                            </a>
			
			                            <p style="margin: 40px 0 0 0; font-size: 13px; color: #888888 !important;">
			                                * 이 링크는 15분 동안만 유효합니다.<br>
			                                * 본인이 요청하지 않았다면 이 메일을 무시하셔도 됩니다.
			                            </p>
			
			                            <div style="margin: 40px 0; border-top: 1px solid #E5E5E5;"></div>
			
			                            <p style="margin: 0 0 8px 0; font-size: 13px; line-height: 1.5; color: #999999 !important;">네임드사주 NAMED</p>
			                            <p style="margin: 0; font-size: 13px; line-height: 1.5; color: #999999 !important;">문의: help@namedsaju.com</p>
			
			                        </td>
			                    </tr>
			                </table>
			
			            </td>
			        </tr>
			    </table>
			
			</body>
			</html>
			""".formatted(resetLink);
	}

	private String createSocialLoginGuideEmailHtml(String displayName) {
		return """
			<!DOCTYPE html>
			<html lang="ko">
			<head>
			    <meta charset="UTF-8">
			    <meta name="viewport" content="width=device-width, initial-scale=1.0">
			</head>
			<body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Malgun Gothic', sans-serif; background-color: #ffffff;">
			    <table width="100%%" cellpadding="0" cellspacing="0" style="max-width: 600px; margin: 0 auto; padding: 40px 20px;">
			        <tr>
			            <td style="text-align: center; padding-bottom: 30px;">
			                <h1 style="color: #333; font-size: 24px;">NAMED</h1>
			            </td>
			        </tr>
			        <tr>
			            <td style="padding: 20px; background-color: #f8f9fa; border-radius: 8px;">
			                <p style="color: #333; font-size: 16px; line-height: 1.6;">
			                    안녕하세요, %s님.<br><br>
			                    비밀번호 재설정을 요청해주셨는데, 해당 이메일은 <b>소셜 로그인</b>으로 가입된 계정입니다.<br><br>
			                    소셜 로그인 계정은 별도의 비밀번호가 없으므로, 가입하신 소셜 로그인(카카오, 구글, 네이버 등)을 이용해주세요.<br><br>
			                    본인이 요청하지 않으셨다면 이 메일을 무시하셔도 됩니다.
			                </p>
			            </td>
			        </tr>
			        <tr>
			            <td style="text-align: center; padding-top: 30px; color: #999; font-size: 12px;">
			                &copy; NAMED. All rights reserved.
			            </td>
			        </tr>
			    </table>
			</body>
			</html>
			""".formatted(displayName);
	}
}
