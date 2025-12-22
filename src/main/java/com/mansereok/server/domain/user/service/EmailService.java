package com.mansereok.server.domain.user.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.model.Body;
import software.amazon.awssdk.services.ses.model.Content;
import software.amazon.awssdk.services.ses.model.Destination;
import software.amazon.awssdk.services.ses.model.Message;
import software.amazon.awssdk.services.ses.model.SendEmailRequest;
import software.amazon.awssdk.services.ses.model.SesException;

@Service
@Slf4j
public class EmailService {

	private final SesClient sesClient;

	@Value("${aws.ses.from-email:welcome@namedsaju.com}")
	private String fromEmail;

	@Value("${aws.ses.from-name:네임드사주}")
	private String fromName;

	public EmailService(
		@Value("${aws.ses.access-key:#{null}}") String accessKey,
		@Value("${aws.ses.secret-key:#{null}}") String secretKey,
		@Value("${aws.ses.region}") String region) {

		// 로컬 개발: Access Key 사용, 배포: IAM Role 자동 감지
		if (accessKey != null && !accessKey.isEmpty() &&
			secretKey != null && !secretKey.isEmpty()) {
			log.info("🔑 Using Access Key credentials (Local Development)");
			AwsBasicCredentials awsCreds = AwsBasicCredentials.create(accessKey, secretKey);
			this.sesClient = SesClient.builder()
				.region(Region.of(region))
				.credentialsProvider(StaticCredentialsProvider.create(awsCreds))
				.build();
		} else {
			log.info("🔐 Using IAM Role credentials (Production)");
			// IAM Role 자동 감지 (DefaultCredentialsProvider)
			this.sesClient = SesClient.builder()
				.region(Region.of(region))
				.build();
		}
	}

	@Async
	public void sendWelcomeEmail(String toEmail, String userName) {
		try {
			String subject = "Welcome NAMED - 진짜 나를 찾는 여정";
			String htmlBody = createWelcomeEmailHtml();

			// 발신자 이름 + 이메일 형식으로 변경
			String fromAddress = fromName + " <" + fromEmail + ">";

			SendEmailRequest request = SendEmailRequest.builder()
				.destination(Destination.builder()
					.toAddresses(toEmail)
					.build())
				.message(Message.builder()
					.subject(Content.builder()
						.charset("UTF-8")
						.data(subject)
						.build())
					.body(Body.builder()
						.html(Content.builder()
							.charset("UTF-8")
							.data(htmlBody)
							.build())
						.build())
					.build())
				.source(fromAddress)  // 변경된 부분
				.build();

			sesClient.sendEmail(request);
			log.info("✅ Welcome-email 전송 완료: {}", toEmail);

		} catch (SesException e) {
			log.error("❌ Welcome-email 전송 실패: {}", e.awsErrorDetails().errorMessage(), e);
		} catch (Exception e) {
			log.error("❌ Welcome-email 전송 중 알 수 없는 오류: {}", e.getMessage(), e);
		}
	}

	public void sendResultReadyEmail(String toEmail, String userName) {
		try {
			String subject = "NAMED 사주 리포트 완성! 지금 이야기를 확인해 보세요.";

			String mypageUrl = "https://www.namedsaju.com/mypage/fortunes";
			String htmlBody = createResultReadyEmailHtml(mypageUrl);

			String fromAddress = fromName + " <" + fromEmail + ">";

			SendEmailRequest request = SendEmailRequest.builder()
				.destination(Destination.builder()
					.toAddresses(toEmail)
					.build())
				.message(Message.builder()
					.subject(Content.builder()
						.charset("UTF-8")
						.data(subject)
						.build())
					.body(Body.builder()
						.html(Content.builder()
							.charset("UTF-8")
							.data(htmlBody)
							.build())
						.build())
					.build())
				.source(fromAddress)
				.build();

			sesClient.sendEmail(request);
			log.info("✅ ResultReady-email 전송 완료: {} (수신자: {})", toEmail, userName);

		} catch (SesException e) {
			log.error("❌ ResultReady-email 전송 실패: {}", e.awsErrorDetails().errorMessage(), e);
		} catch (Exception e) {
			log.error("❌ ResultReady-email 전송 중 알 수 없는 오류: {}", e.getMessage(), e);
		}
	}

	private String createWelcomeEmailHtml() {
		return """
			<!DOCTYPE html>
			<html lang="ko">
			<head>
			    <meta charset="UTF-8">
			    <meta name="viewport" content="width=device-width, initial-scale=1.0">
			    <meta name="color-scheme" content="light only">
			    <meta name="supported-color-schemes" content="light">
			    <title>NAMED</title>
			    <style type="text/css">
			        /* 다크모드 방지 */
			        :root {
			            color-scheme: light only;
			            supported-color-schemes: light;
			        }
			
			        /* 모든 요소에 다크모드 방지 적용 */
			        * {
			            color-scheme: light only !important;
			        }
			
			        /* 이미지 다크모드 필터 방지 */
			        img {
			            -webkit-filter: none !important;
			            filter: none !important;
			        }
			
			        /* 배경색 강제 지정 */
			        body, table, td {
			            background-color: #ffffff !important;
			        }
			
			        /* 다크모드에서도 흰 배경 유지 */
			        .email-container {
			            background-color: #ffffff !important;
			        }
			
			        /* iOS/Mac Mail 다크모드 방지 */
			        @media (prefers-color-scheme: dark) {
			            body, table, td, .email-container {
			                background-color: #ffffff !important;
			                color: #000000 !important;
			            }
			            img {
			                opacity: 1 !important;
			            }
			        }
			    </style>
			    <!--[if gte mso 9]>
			    <xml>
			        <o:OfficeDocumentSettings>
			            <o:AllowPNG/>
			            <o:PixelsPerInch>96</o:PixelsPerInch>
			        </o:OfficeDocumentSettings>
			    </xml>
			    <![endif]-->
			</head>
			<body style="margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, 'Malgun Gothic', 'Apple SD Gothic Neo', sans-serif; background-color: #ffffff !important;">
			
			    <div style="display: none; max-height: 0px; overflow: hidden;">
			        진짜 '나'를 찾는 여정 NAMED에 오신 걸 환영합니다.
			    </div>
			
			    <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="100%%" style="background-color: #ffffff !important;" class="email-container">
			        <tr>
			            <td align="center" style="padding: 40px 20px; background-color: #ffffff !important;">
			
			                <!-- 그라데이션 테두리 -->
			                <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="700" style="max-width: 700px; background-image: url('https://named-logo.s3.ap-northeast-2.amazonaws.com/email-gradient-border.png'); background-size: cover; background-position: center; background-repeat: no-repeat; background-color: #00D4FF;">
			                    <!--[if gte mso 9]>
			                    <v:rect xmlns:v="urn:schemas-microsoft-com:vml" fill="true" stroke="false" style="width:700px;">
			                    <v:fill type="frame" src="https://named-logo.s3.ap-northeast-2.amazonaws.com/email-gradient-border.png" />
			                    <v:textbox style="mso-fit-shape-to-text:true" inset="0,0,0,0">
			                    <![endif]-->
			                    <tr>
			                        <td style="padding: 15px;">
			
			                            <!-- 흰색 내부 컨텐츠 -->
			                            <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="100%%" style="background-color: #ffffff !important;">
			                                <tr>
			                                    <td style="padding: 60px 50px; text-align: center; background-color: #ffffff !important;">
			
			                                        <!-- 로고 -->
			                                        <div style="background-color: #ffffff !important; padding: 10px 0;">
			                                            <img src="https://named-logo.s3.ap-northeast-2.amazonaws.com/named-logo.png" 
			                                                 alt="NAMED Logo" 
			                                                 width="80" 
			                                                 height="80" 
			                                                 style="display: block; margin: 0 auto 35px; border: 0; max-width: 80px; height: auto;">
			                                        </div>
			
			                                        <!-- 제목 -->
			                                        <h1 style="margin: 0 0 25px 0; font-size: 40px; font-weight: 400; color: #000000 !important; letter-spacing: -1px; line-height: 1.2;">
			                                            Welcome <span style="font-weight: 700; color: #000000 !important;">NAMED</span>
			                                        </h1>
			
			                                        <!-- 서브 타이틀 -->
			                                        <p style="margin: 0 0 50px 0; font-size: 17px; line-height: 1.6; color: #333333 !important;">
			                                            진짜 '나'를 찾는 여정<br>
			                                            NAMED에 오신 걸 환영합니다.
			                                        </p>
			
			                                        <!-- 회색 배경 본문 -->
			                                        <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="100%%" style="background-color: #F8F8F8 !important; margin: 0 0 40px 0;">
			                                            <tr>
			                                                <td style="padding: 40px 35px; background-color: #F8F8F8 !important;">
			                                                    <p style="margin: 0 0 5px 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        안녕하세요.
			                                                    </p>
			                                                    <p style="margin: 0 0 20px 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        네임드와 함께해주셔서 감사합니다.
			                                                    </p>
			
			                                                    <p style="margin: 0 0 5px 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        우리는 좋아한다는 감정이
			                                                    </p>
			                                                    <p style="margin: 0 0 5px 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        한 사람의 세계를 바꾸는 힘이라고 믿습니다.
			                                                    </p>
			                                                    <p style="margin: 0 0 5px 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        무대, 한 장면, 한 문장, 그 작은 조각이
			                                                    </p>
			                                                    <p style="margin: 0 0 20px 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        우리의 하루를 버티게 했던 적 있죠.
			                                                    </p>
			
			                                                    <p style="margin: 0 0 5px 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        마음 다해 좋아했을 뿐인데
			                                                    </p>
			                                                    <p style="margin: 0 0 20px 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        그것들이 밝히고 있는 세계를 기록 채우기도 합니다.
			                                                    </p>
			
			                                                    <p style="margin: 0 0 5px 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        행복해지는 법은 간단한데요.
			                                                    </p>
			                                                    <p style="margin: 0 0 20px 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                                        좋아하는 걸 더 자주 하면 됩니다.
			                                                    </p>
			
			                                                    <p style="margin: 0; font-size: 16px; line-height: 1.4; color: #333333 !important; font-weight: 600; text-align: center;">
			                                                        앞으로 더 자주 만나요!
			                                                    </p>
			                                                </td>
			                                            </tr>
			                                        </table>
			
			                                        <!-- 소셜 미디어 안내 문구 -->
			                                        <p style="margin: 0 0 25px 0; font-size: 15px; line-height: 1.5; color: #666666 !important; text-align: center; font-weight: 500;">
			                                            다양한 곳에서 네임드사주를 만나 보세요!
			                                        </p>
			
			                                        <!-- 소셜 미디어 아이콘 (개별 margin 조정) -->
			                                        <div style="background-color: #ffffff !important; padding: 0 0 20px 0;">
			                                            <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="100%%" style="margin: 0 0 30px 0;">
			                                                <tr>
			                                                    <td align="center" style="background-color: #ffffff !important;">
			                                                        <!-- Instagram: 40px - 기본 margin -->
			                                                        <a href="https://www.instagram.com/namedsaju_official/" style="display: inline-block; margin: 0 10px; text-decoration: none;">
			                                                            <img src="https://named-logo.s3.ap-northeast-2.amazonaws.com/instagram.png" 
			                                                                 alt="Instagram" 
			                                                                 width="40" 
			                                                                 height="40" 
			                                                                 style="border: 0; display: inline-block; vertical-align: middle; max-width: 40px;">
			                                                        </a>
			                                                        <!-- X: 37px - margin 증가 (1.5px씩) -->
			                                                        <a href="https://x.com/namedsaju?s=11" style="display: inline-block; margin: 0 11.5px; text-decoration: none;">
			                                                            <img src="https://named-logo.s3.ap-northeast-2.amazonaws.com/X.png" 
			                                                                 alt="X" 
			                                                                 width="37" 
			                                                                 height="37" 
			                                                                 style="border: 0; display: inline-block; vertical-align: middle; max-width: 40px;">
			                                                        </a>
			                                                        <!-- Naver Blog: 33px - margin 더 증가 (3.5px씩) -->
			                                                        <a href="https://blog.naver.com/namedsaju_official" style="display: inline-block; margin: 0 13.5px; text-decoration: none;">
			                                                            <img src="https://named-logo.s3.ap-northeast-2.amazonaws.com/naver_blog.png" 
			                                                                 alt="Naver Blog" 
			                                                                 width="33" 
			                                                                 height="33" 
			                                                                 style="border: 0; display: inline-block; vertical-align: middle; max-width: 50px;">
			                                                        </a>
			                                                        <!-- Homepage: 40px - 기본 margin -->
			                                                        <a href="https://www.namedsaju.com/" style="display: inline-block; margin: 0 10px; text-decoration: none;">
			                                                            <img src="https://named-logo.s3.ap-northeast-2.amazonaws.com/homepage.png" 
			                                                                 alt="Homepage" 
			                                                                 width="40" 
			                                                                 height="40" 
			                                                                 style="border: 0; display: inline-block; vertical-align: middle; max-width: 40px;">
			                                                        </a>
			                                                    </td>
			                                                </tr>
			                                            </table>
			                                        </div>
			
			                                        <!-- 하단 정보 -->
			                                        <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="100%%" style="border-top: 1px solid #E5E5E5; padding-top: 35px; background-color: #ffffff !important;">
			                                            <tr>
			                                                <td align="center" style="background-color: #ffffff !important;">
			                                                    <p style="margin: 0 0 6px 0; font-size: 13px; line-height: 1.5; color: #999999 !important;">
			                                                        네임드사주 NAMED
			                                                    </p>
			                                                    <p style="margin: 0 0 6px 0; font-size: 13px; line-height: 1.5; color: #999999 !important;">
			                                                        문의: help@namedsaju.com
			                                                    </p>
			                                                    <p style="margin: 0; font-size: 13px; line-height: 1.5;">
			                                                        <a href="https://www.namedsaju.com" style="color: #6B9FF5 !important; text-decoration: underline;">수신거부</a>
			                                                        <span style="color: #CCCCCC !important;"> | </span>
			                                                        <a href="https://www.namedsaju.com" style="color: #6B9FF5 !important; text-decoration: underline;">Unsubscribe</a>
			                                                    </p>
			                                                </td>
			                                            </tr>
			                                        </table>
			
			                                    </td>
			                                </tr>
			                            </table>
			
			                        </td>
			                    </tr>
			                    <!--[if gte mso 9]>
			                    </v:textbox>
			                    </v:rect>
			                    <![endif]-->
			                </table>
			
			            </td>
			        </tr>
			    </table>
			
			</body>
			</html>
			""";
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

	private String createReviewRewardEmailHtml(String discountCode, int amount) {

		return """
			<!DOCTYPE html>
			<html lang="ko">
			<head>
			    <meta charset="UTF-8">
			    <meta name="viewport" content="width=device-width, initial-scale=1.0">
			    <meta name="color-scheme" content="light only">
			    <meta name="supported-color-schemes" content="light">
			    <title>[NAMED] 소중한 리뷰 작성 감사드립니다.</title>
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
			                                            <h1 style="margin: 0 0 25px 0; font-size: 35px; font-weight: 700; color: #000000 !important; letter-spacing: -1px; line-height: 1.2;">
			                                                리뷰 작성 감사 보상!
			                                            </h1>
			                                        </div>
			
			                                        <p style="margin: 0 0 30px 0; font-size: 18px; line-height: 1.6; color: #333333 !important; font-weight: 500;">
			                                            회원님의 소중한 리뷰에 감사드립니다.
			                                        </p>
			                                        <p style="margin: 0 0 40px 0; font-size: 17px; line-height: 1.6; color: #555555 !important;">
			                                            다음 결제 시 사용할 수 있는 %s원 할인 코드입니다.
			                                        </p>
			
			                                        <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="100%%" style="background-color: #F0F8FF !important; border: 1px dashed #AEE0FF; margin: 0 0 40px 0; border-radius: 8px;">
			                                            <tr>
			                                                <td style="padding: 25px 35px; background-color: #F0F8FF !important;">
			                                                    <p style="margin: 0 0 10px 0; font-size: 16px; line-height: 1.4; color: #005A87 !important; text-align: center; font-weight: 700;">
			                                                        할인 금액: **%,d원**
			                                                    </p>
			                                                    <p style="margin: 0 0 5px 0; font-size: 14px; line-height: 1.4; color: #666666 !important; text-align: center;">
			                                                        사용 기한: 30일
			                                                    </p>
			                                                    <h2 style="margin: 15px 0 0 0; font-size: 26px; font-weight: 700; color: #000000 !important; letter-spacing: 2px;">
			                                                        %s
			                                                    </h2>
			                                                </td>
			                                            </tr>
			                                        </table>
			
			                                        <p style="margin: 0; font-size: 16px; line-height: 1.4; color: #333333 !important; text-align: center;">
			                                            앞으로도 NAMED에 많은 관심과 이용 부탁드립니다.
			                                        </p>
			
			                                        <!-- 하단 정보 -->
			                                        <table role="presentation" cellspacing="0" cellpadding="0" border="0" width="100%%" style="border-top: 1px solid #E5E5E5; padding-top: 35px; margin-top: 50px; background-color: #ffffff !important;">
			                                            <tr>
			                                                <td align="center" style="background-color: #ffffff !important;">
			                                                    <p style="margin: 0 0 6px 0; font-size: 13px; line-height: 1.5; color: #999999 !important;">네임드사주 NAMED</p>
			                                                    <p style="margin: 0 0 6px 0; font-size: 13px; line-height: 1.5; color: #999999 !important;">문의: help@namedsaju.com</p>
			                                                    <p style="margin: 0; font-size: 13px; line-height: 1.5;">
			                                                        <a href="https://www.namedsaju.com" style="color: #6B9FF5 !important; text-decoration: underline;">수신거부</a>
			                                                        <span style="color: #CCCCCC !important;"> | </span>
			                                                        <a href="https://www.namedsaju.com" style="color: #6B9FF5 !important; text-decoration: underline;">Unsubscribe</a>
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
			""".formatted(amount, amount, discountCode);
	}

	@Async
	public void sendPasswordResetEmail(String toEmail, String token) {
		try {
			// 프론트엔드의 비밀번호 변경 페이지 URL
			String resetLink = "https://www.namedsaju.com/auth/reset-password?token=" + token;

			String subject = "[NAMED] 비밀번호 재설정 안내";
			String htmlBody = createPasswordResetEmailHtml(resetLink);

			// 발신자 설정
			String fromAddress = fromName + " <" + fromEmail + ">";

			// 이메일 요청 객체 생성
			SendEmailRequest request = SendEmailRequest.builder()
				.destination(Destination.builder()
					.toAddresses(toEmail)
					.build())
				.message(Message.builder()
					.subject(Content.builder()
						.charset("UTF-8")
						.data(subject)
						.build())
					.body(Body.builder()
						.html(Content.builder()
							.charset("UTF-8")
							.data(htmlBody)
							.build())
						.build())
					.build())
				.source(fromAddress)
				.build();

			// 전송
			sesClient.sendEmail(request);
			log.info("✅ Password-reset-email 전송 완료: {}", toEmail);

		} catch (SesException e) {
			log.error("❌ Password-reset-email 전송 실패: {}", e.awsErrorDetails().errorMessage(), e);
		} catch (Exception e) {
			log.error("❌ Password-reset-email 전송 중 알 수 없는 오류: {}", e.getMessage(), e);
		}
	}

	private String createPasswordResetEmailHtml(String resetLink) {
		return """
			    <!DOCTYPE html>
			    <html lang="ko">
			    <body style="...">
			        <p>[비밀번호 재설정]</p>
			        <p>아래 버튼을 클릭하여 새로운 비밀번호를 설정해주세요.</p>
			        <p>(링크는 15분간 유효합니다.)</p>
			
			        <a href="%s" style="padding: 12px 20px; background-color: #000; color: #fff; text-decoration: none; border-radius: 5px;">
			            비밀번호 재설정하기
			        </a>
			
			        </body>
			    </html>
			""".formatted(resetLink);
	}
}
