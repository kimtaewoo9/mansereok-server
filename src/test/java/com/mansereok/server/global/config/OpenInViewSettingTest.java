package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * 모든 환경의 설정 파일이 open-in-view 를 꺼 두는지 확인한다.
 *
 * <p>스프링 컨텍스트와 DB 없이 설정 파일만 읽으므로 기본 {@code ./gradlew test} 에서 돈다. 누가 설정을 true 로 되돌리거나 지우면
 * 여기서 바로 실패한다. 꺼진 설정에서 커넥션을 실제로 돌려주는지는 OpenInViewOffMySqlTest 가 실제 MySQL 로 확인한다.
 */
class OpenInViewSettingTest {

	@ParameterizedTest(name = "{0}")
	@ValueSource(strings = {"application.yml", "application-dev.yml", "application-prod.yml"})
	@DisplayName("모든 환경의 설정 파일이 open-in-view 를 false 로 적어 둔다")
	void everyProfileTurnsOffOpenInView(String fileName) {
		// given
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource(fileName));

		// when
		Properties properties = yaml.getObject();

		// then
		assertThat(properties).isNotNull();
		assertThat(properties.get("spring.jpa.open-in-view")).as(fileName).isEqualTo(false);
	}
}
