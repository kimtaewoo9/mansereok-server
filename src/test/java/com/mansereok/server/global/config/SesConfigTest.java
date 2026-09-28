package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.ses.SesClient;

/**
 * SES 클라이언트가 설정의 접근 키로 접속할지, AWS SDK 기본 자격 증명 순서(환경변수, 프로필 파일, 컨테이너·인스턴스 역할)로
 * 접속할지를 제대로 고르는지 확인한다.
 *
 * <p>클라이언트를 만들기만 하고 SES 에 요청을 보내지 않으므로 네트워크가 필요 없다. 고른 자격 증명은
 * {@code serviceClientConfiguration().credentialsProvider()} 로 읽는다.
 */
class SesConfigTest {

	private static final String REGION = "ap-northeast-2";

	private final SesConfig sesConfig = new SesConfig();

	@Test
	@DisplayName("접근 키와 비밀 키가 둘 다 있으면 그 키로 접속한다")
	void usesConfiguredKeysWhenBothPresent() {
		// when
		try (SesClient client = sesConfig.sesClient("local-access", "local-secret", REGION)) {

			// then
			assertThat(client.serviceClientConfiguration().credentialsProvider())
				.isInstanceOf(StaticCredentialsProvider.class);
			AwsCredentials credentials = ((StaticCredentialsProvider)client.serviceClientConfiguration()
				.credentialsProvider()).resolveCredentials();
			assertThat(credentials.accessKeyId()).isEqualTo("local-access");
			assertThat(credentials.secretAccessKey()).isEqualTo("local-secret");
		}
	}

	@ParameterizedTest(name = "[{index}] 접근 키 [{0}], 비밀 키 [{1}]")
	@DisplayName("접근 키와 비밀 키 중 하나라도 없거나 비어 있으면 AWS SDK 기본 자격 증명 순서로 접속한다")
	@CsvSource(textBlock = """
		# 접근 키, 비밀 키 (빈 칸은 null, '' 는 빈 문자열)
		            , local-secret
		local-access,
		            ,
		''          , local-secret
		local-access, ''
		''          , ''
		""")
	void fallsBackToSdkDefaultWhenAnyKeyMissing(String accessKey, String secretKey) {
		// when
		try (SesClient client = sesConfig.sesClient(accessKey, secretKey, REGION)) {

			// then
			assertThat(client.serviceClientConfiguration().credentialsProvider())
				.isInstanceOf(DefaultCredentialsProvider.class);
		}
	}
}
