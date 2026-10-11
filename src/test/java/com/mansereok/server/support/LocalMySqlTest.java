package com.mansereok.server.support;

import com.mansereok.server.domain.interpret.service.S3UploadService;
import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.notification.service.SlackNotificationService;
import com.mansereok.server.domain.user.service.EmailService;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 로컬 Docker MySQL(127.0.0.1:3307, mansereok_test 처럼 이름에 _test 가 붙은 스키마)에 붙는 테스트의 바탕 클래스.
 *
 * <p>잠금, UNIQUE, 조건부 UPDATE 처럼 DB 가 지키는 규칙은 목으로 흉내 낼 수 없어 실제 MySQL 로 확인한다. 기본
 * {@code ./gradlew test} 에서는 빠지고 {@code ./gradlew concurrencyTest} 로만 돈다. 밖으로 나가는 호출 중 모든 영역이 함께
 * 쓰는 것(Discord, Slack, SES 메일, S3)만 여기서 목으로 바꾼다.
 *
 * <p>이 클래스를 상속한 테스트는 다음을 지킨다.
 * <ul>
 *   <li>테스트 클래스와 메서드에 {@code @Transactional} 을 붙이지 않는다. 붙이면 준비한 데이터가 커밋되지 않아 작업 스레드에서
 *   보이지 않고, 테스트 스레드가 쥔 잠금 때문에 작업 스레드가 멈춘다.</li>
 *   <li>데이터는 실행마다 다른 키(UUID 앞 8자리)로 만들고, 뒤 정리에서 그 키로 만든 행만 지운다. {@code deleteAll} 은 쓰지
 *   않는다. 같은 스키마에 manses 기초 데이터와 다른 테스트가 만든 행이 함께 있다.</li>
 *   <li>DB 에 남은 결과는 JPA 캐시를 거치지 않고 {@link #jdbcTemplate} 로 센다.</li>
 *   <li>스케줄러가 돌기를 기다리지 않고 서비스를 직접 부른다. 시각 조건이 필요하면 created_at 같은 컬럼을
 *   {@link #jdbcTemplate} 로 옮겨 맞춘다.</li>
 *   <li>결제·해석처럼 한 영역에서만 필요한 바깥 시스템 목(포트원, OpenAI 등)은 이 클래스를 상속한 영역별 바탕 클래스
 *   (예: PaymentMySqlTest)에 둔다. 목 조합이 같은 테스트끼리는 스프링 컨텍스트를 다시 띄우지 않는다.</li>
 *   <li>로컬 스키마가 꼬이면 manses 를 뺀 표를 지우고 다시 돌린다. ddl-auto: update 가 표를 다시 만든다.</li>
 * </ul>
 *
 * <p>실행: docker 로 mysql:8.0 을 127.0.0.1:3307 에 띄운 뒤 {@code ./gradlew concurrencyTest}. 포트와 스키마는
 * CONCURRENCY_DB_PORT, CONCURRENCY_DB_NAME 환경변수로 바꾼다.
 */
@Tag("concurrency")
@SpringBootTest
@ActiveProfiles("concurrency")
@ContextConfiguration(initializers = LocalMySqlTest.ConfiguredDatabaseCheck.class)
public abstract class LocalMySqlTest {

	@MockitoBean
	protected DiscordNotificationService discordNotificationService;

	@MockitoBean
	protected SlackNotificationService slackNotificationService;

	@MockitoBean
	protected EmailService emailService;

	@MockitoBean
	protected S3UploadService s3UploadService;

	@Autowired
	protected JdbcTemplate jdbcTemplate;

	/**
	 * 테스트마다 실제로 붙은 연결의 URL 과 스키마 이름을 확인한다.
	 */
	@BeforeEach
	protected final void checkConnectedToLocalTestDatabase() {
		String jdbcUrl = jdbcTemplate.execute(
			(ConnectionCallback<String>) connection -> connection.getMetaData().getURL());
		String schemaName = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
		LocalDatabaseGuard.check(jdbcUrl, schemaName);
	}

	/**
	 * 스프링이 DB 에 처음 붙기 전에, 테스트가 붙을 DB 주소를 두 번 확인한다.
	 *
	 * <ol>
	 *   <li>컨텍스트를 만들기 시작할 때 spring.datasource.url 설정값을 본다.</li>
	 *   <li>커넥션 풀(HikariDataSource) 빈이 만들어진 직후, 첫 커넥션을 열기 전에 풀이 실제로 쓸 주소(getJdbcUrl)를 본다.
	 *   spring.datasource.hikari.jdbc-url(환경변수 SPRING_DATASOURCE_HIKARI_JDBCURL)처럼 풀 주소만 따로 덮어쓴 경우는 첫 번째
	 *   확인을 지나치므로 여기서 멈춘다.</li>
	 * </ol>
	 *
	 * <p>{@link #checkConnectedToLocalTestDatabase()} 는 컨텍스트가 뜬 뒤에 돌기 때문에, 그 전에 ddl-auto: update 가 다른
	 * DB 의 표를 고치는 것까지는 막지 못한다. 두 확인은 Hibernate 가 표를 고치기 전에 멈춘다. HikariDataSource 가 아닌
	 * DataSource 는 커넥션을 열기 전에 주소를 알 수 없으므로 멈춘다.
	 */
	static class ConfiguredDatabaseCheck implements
		ApplicationContextInitializer<ConfigurableApplicationContext> {

		@Override
		public void initialize(ConfigurableApplicationContext context) {
			LocalDatabaseGuard.checkUrl(context.getEnvironment().getProperty("spring.datasource.url"));
			context.getBeanFactory().addBeanPostProcessor(new ConnectionPoolUrlCheck());
		}
	}

	/**
	 * DataSource 빈이 설정 값을 모두 받은 뒤(초기화 뒤) 풀이 쓸 주소를 확인한다. HikariDataSource 는 첫 커넥션을 요청받을 때
	 * 풀을 여므로, 이 시점에는 아직 DB 에 붙지 않았다.
	 */
	private static final class ConnectionPoolUrlCheck implements BeanPostProcessor {

		@Override
		public Object postProcessAfterInitialization(Object bean, String beanName) {
			if (bean instanceof HikariDataSource hikariDataSource) {
				LocalDatabaseGuard.checkUrl(hikariDataSource.getJdbcUrl());
			} else if (bean instanceof DataSource) {
				throw new IllegalStateException("HikariDataSource 가 아닌 DataSource 는 커넥션을 열기 전에 주소를 확인할 수 없다: "
					+ beanName + " (" + bean.getClass().getName() + ")");
			}
			return bean;
		}
	}
}
