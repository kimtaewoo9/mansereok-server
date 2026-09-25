package com.mansereok.server.support;

import com.mansereok.server.domain.interpret.service.S3UploadService;
import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.notification.service.SlackNotificationService;
import com.mansereok.server.domain.user.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
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
 * {@code ./gradlew test} 에서는 빠지고 {@code ./gradlew concurrencyTest} 로만 돈다. 밖으로 나가는 호출(Discord, Slack,
 * SES 메일, S3)은 세 스택 모두에 있는 것만 여기서 목으로 바꾼다.
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
 *   <li>스택마다 따로 필요한 바깥 시스템 목(포트원, OpenAI 등)은 이 클래스를 상속한 스택별 바탕 클래스에 둔다. 목 조합이
 *   같은 테스트끼리는 스프링 컨텍스트를 다시 띄우지 않는다.</li>
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
	 * 스프링이 DB 에 붙기 전에 설정된 spring.datasource.url 을 확인한다.
	 *
	 * <p>{@link #checkConnectedToLocalTestDatabase()} 는 컨텍스트가 뜬 뒤에 돌기 때문에, 그 전에 ddl-auto: update 가 다른
	 * DB 의 표를 고치는 것까지는 막지 못한다. SPRING_DATASOURCE_URL 같은 환경변수가 이 프로필의 URL 을 덮어쓴 경우를 여기서
	 * 먼저 멈춘다.
	 */
	static class ConfiguredDatabaseCheck implements
		ApplicationContextInitializer<ConfigurableApplicationContext> {

		@Override
		public void initialize(ConfigurableApplicationContext context) {
			LocalDatabaseGuard.checkUrl(context.getEnvironment().getProperty("spring.datasource.url"));
		}
	}
}
