package com.mansereok.server.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;
import software.amazon.awssdk.services.ses.SesClientBuilder;

/**
 * 메일 발송(EmailService)에 쓰는 SES 클라이언트.
 *
 * <p>aws.ses.access-key 와 aws.ses.secret-key 가 둘 다 있으면 그 키로 접속한다(로컬 개발). 하나라도 없으면 AWS SDK 가 기본
 * 순서대로 자격 증명을 찾는다(배포 서버의 IAM 역할).
 *
 * <p>클라이언트는 HTTP 연결 풀을 쥐고 있어서, 애플리케이션이 내려갈 때 스프링이 close 를 불러 돌려주게 한다. EmailService 가 직접
 * 만들지 않고 주입받으므로 테스트에서는 목으로 바꿔 끼운다.
 */
@Configuration
@Slf4j
public class SesConfig {

	@Bean(destroyMethod = "close")
	public SesClient sesClient(
		@Value("${aws.ses.access-key:#{null}}") String accessKey,
		@Value("${aws.ses.secret-key:#{null}}") String secretKey,
		@Value("${aws.ses.region}") String region) {
		SesClientBuilder builder = SesClient.builder().region(Region.of(region));
		if (StringUtils.hasLength(accessKey) && StringUtils.hasLength(secretKey)) {
			log.info("SES 는 설정의 접근 키로 접속한다(로컬 개발)");
			builder.credentialsProvider(
				StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
		} else {
			log.info("SES 는 AWS SDK 기본 자격 증명 순서로 접속한다(배포 서버는 IAM 역할)");
		}
		return builder.build();
	}
}
