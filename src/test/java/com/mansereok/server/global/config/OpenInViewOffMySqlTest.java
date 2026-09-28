package com.mansereok.server.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.mansereok.server.domain.auth.util.JwtUtil;
import com.mansereok.server.domain.auth.util.RefreshTokenCookies;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.RefreshTokenService;
import com.mansereok.server.support.LocalMySqlTest;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * open-in-view 를 끈 설정에서 요청이 트랜잭션이 끝난 뒤 DB 커넥션을 풀에 돌려주는지, 그리고 이 설정에서도 재발급과 탈퇴가 그대로
 * 되는지 실제 MySQL 과 웹 요청(MockMvc)으로 확인한다.
 *
 * <p>open-in-view 가 켜져 있으면 요청마다 EntityManager 하나를 요청 끝까지 열어 두고, Hibernate 는 처음 잡은 커넥션을 그
 * EntityManager 가 닫힐 때까지 놓지 않는다. 그래서 트랜잭션이 커밋된 뒤 외부 API 를 기다리는 동안에도 커넥션을 쥔다. 이 동작은
 * 웹 요청을 거쳐야 나타나므로 서비스를 직접 부르지 않고 MockMvc 로 부른다.
 *
 * <p>커넥션 수는 확인용 컨트롤러({@link ConnectionCountController})가 트랜잭션 하나를 커밋한 직후, 같은 요청 안에서 잰다. 커밋 뒤
 * 리스너(AFTER_COMMIT) 안에서는 트랜잭션 정리 전이라 설정과 상관없이 커넥션을 쥐고 있으므로 거기서는 재지 않는다.
 *
 * <p>세 설정 파일이 open-in-view 를 false 로 적어 두었는지는 DB 가 필요 없어 기본 test 에서 도는 {@link OpenInViewSettingTest} 가
 * 확인한다. 여기서는 그 설정으로 뜬 컨텍스트의 동작만 본다.
 *
 * <p>회원과 토큰은 이번 실행의 runId 를 넣은 이메일로 만들고, 뒤 정리에서 그 회원의 행만 지운다.
 */
@AutoConfigureMockMvc
@Import(OpenInViewOffMySqlTest.ConnectionCountController.class)
class OpenInViewOffMySqlTest extends LocalMySqlTest {

	private static final String CONNECTION_COUNT_URL = "/test/open-in-view/active-connections";
	private static final String XSRF_TOKEN = "open-in-view-test-xsrf-token";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ApplicationContext applicationContext;
	@Autowired
	private HikariDataSource dataSource;
	@Autowired
	private JwtUtil jwtUtil;
	@Autowired
	private RefreshTokenService refreshTokenService;
	@Autowired
	private UserRepository userRepository;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String email = "open-in-view-" + runId + "@example.com";

	private User member;

	@BeforeEach
	void saveMemberAndWaitForIdlePool() {
		member = userRepository.save(User.create(email, "커넥션회원", "encoded-password", email,
			LocalDate.of(1990, 1, 1), Gender.FEMALE, true, true, false));
		// 컨텍스트가 뜰 때 한 번 도는 스케줄러가 아직 커넥션을 쥐고 있으면 잰 값이 흔들린다. 풀이 비기를 기다린다.
		await().atMost(Duration.ofSeconds(5))
			.until(() -> dataSource.getHikariPoolMXBean().getActiveConnections() == 0);
	}

	@AfterEach
	void deleteRowsOfThisRun() {
		// users 를 외래 키로 가리키므로 users 행보다 먼저 지운다. 탈퇴 테스트는 이미 지웠으므로 0 행이다.
		jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id = ?", member.getId());
		jdbcTemplate.update("DELETE FROM users WHERE id = ?", member.getId());
	}

	@Test
	@DisplayName("open-in-view 가 꺼져 있으면 요청마다 EntityManager 를 여는 인터셉터가 등록되지 않는다")
	void openEntityManagerInViewInterceptorIsNotRegistered() {
		// when
		Map<String, OpenEntityManagerInViewInterceptor> interceptors =
			applicationContext.getBeansOfType(OpenEntityManagerInViewInterceptor.class);

		// then
		assertThat(interceptors).isEmpty();
	}

	@Test
	@DisplayName("요청 안에서 트랜잭션이 커밋되면, 요청이 끝나기 전이라도 사용 중인 커넥션이 0 개다")
	void connectionIsReturnedWhenTransactionEndsBeforeRequestEnds() throws Exception {
		// when
		MvcResult result = mockMvc.perform(get(CONNECTION_COUNT_URL).header(HttpHeaders.AUTHORIZATION, bearer()))
			.andReturn();

		// then
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(result.getResponse().getContentAsString()).isEqualTo("0");
	}

	@Test
	@DisplayName("리프레시 쿠키로 재발급하면 트랜잭션 밖에서 회원 값을 꺼내도 지연 로딩 예외 없이 200 과 회원 이름을 돌려준다")
	void refreshSucceedsWithoutLazyLoadingOutsideTransaction() throws Exception {
		// given
		String refreshToken = refreshTokenService.issue(member);

		// when
		MvcResult result = mockMvc.perform(withXsrfToken(post("/api/auth/refresh"))
			.cookie(new Cookie(RefreshTokenCookies.NAME, refreshToken))).andReturn();

		// then
		assertThat(result.getResolvedException()).as("재발급 중 난 예외").isNull();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		jsonPath("$.userDto.name").value("커넥션회원").match(result);
	}

	@Test
	@DisplayName("로그인한 회원이 탈퇴하면 200 을 받고 회원 행과 리프레시 토큰 행이 지워진다")
	void withdrawalSucceeds() throws Exception {
		// given
		refreshTokenService.issue(member);

		// when
		MvcResult result = mockMvc.perform(withXsrfToken(delete("/api/v1/users/me"))
			.header(HttpHeaders.AUTHORIZATION, bearer())).andReturn();

		// then
		assertThat(result.getResolvedException()).as("탈퇴 중 난 예외").isNull();
		assertThat(result.getResponse().getStatus()).isEqualTo(200);
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class,
			member.getId())).as("회원 행").isZero();
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?",
			Integer.class, member.getId())).as("리프레시 토큰 행").isZero();
	}

	/**
	 * open-in-view 를 켜면 같은 요청이 커밋 뒤에도 커넥션 1 개를 쥔다. 확인용 컨트롤러가 정말 이 차이를 재는지 보여 준다.
	 */
	@Nested
	@DisplayName("open-in-view 를 켜면")
	@TestPropertySource(properties = "spring.jpa.open-in-view=true")
	class WhenOpenInViewIsOn {

		// 이 중첩 클래스의 스프링 컨텍스트(open-in-view 켬)에서 받은 것. 바깥 클래스의 mockMvc·dataSource 는 꺼진 컨텍스트의 것이다.
		@Autowired
		private MockMvc mockMvcWithOpenInView;
		@Autowired
		private HikariDataSource dataSourceWithOpenInView;

		@BeforeEach
		void waitForIdlePool() {
			await().atMost(Duration.ofSeconds(5))
				.until(() -> dataSourceWithOpenInView.getHikariPoolMXBean().getActiveConnections() == 0);
		}

		@Test
		@DisplayName("open-in-view 가 켜져 있으면 트랜잭션이 커밋된 뒤에도 요청이 끝날 때까지 커넥션 1 개를 쥔다")
		void connectionIsHeldUntilRequestEnds() throws Exception {
			// when
			MvcResult result = mockMvcWithOpenInView.perform(
				get(CONNECTION_COUNT_URL).header(HttpHeaders.AUTHORIZATION, bearer())).andReturn();

			// then
			assertThat(result.getResponse().getStatus()).isEqualTo(200);
			assertThat(result.getResponse().getContentAsString()).isEqualTo("1");
		}
	}

	private String bearer() {
		return "Bearer " + jwtUtil.generateAccessToken(email, Map.of("role", "ROLE_USER"));
	}

	/**
	 * 프론트엔드처럼 쓰기 요청에 XSRF-TOKEN 쿠키와 같은 값의 X-XSRF-TOKEN 헤더를 싣는다. 재발급 경로는 이 검사에서 빠져 있지만
	 * 브라우저는 같이 싣는다.
	 */
	private static MockHttpServletRequestBuilder withXsrfToken(MockHttpServletRequestBuilder request) {
		return request.cookie(new Cookie("XSRF-TOKEN", XSRF_TOKEN)).header("X-XSRF-TOKEN", XSRF_TOKEN);
	}

	/**
	 * 트랜잭션 하나에서 로그인한 회원을 읽어 커밋한 뒤, 같은 요청 안에서 풀의 사용 중 커넥션 수를 돌려준다. 같은 풀을 쓰는 곳은
	 * 스케줄러 둘(OrderExpirationScheduler 는 30분마다, RefreshTokenCleanupScheduler 는 새벽 4시 30분)뿐이다. 컨텍스트가 뜰 때
	 * 도는 스케줄러가 끝나기를 기다린 뒤(테스트 전에 풀이 비기를 기다린다)에는 다른 사용자가 거의 없으므로, 잰 값은 이 요청이 쥔
	 * 커넥션 수로 본다.
	 */
	@RestController
	static class ConnectionCountController {

		private final UserRepository userRepository;
		private final TransactionTemplate transactionTemplate;
		private final HikariDataSource dataSource;

		ConnectionCountController(UserRepository userRepository, PlatformTransactionManager transactionManager,
			HikariDataSource dataSource) {
			this.userRepository = userRepository;
			this.transactionTemplate = new TransactionTemplate(transactionManager);
			this.dataSource = dataSource;
		}

		@GetMapping(CONNECTION_COUNT_URL)
		int activeConnectionsAfterTransaction(@AuthenticationPrincipal String username) {
			transactionTemplate.executeWithoutResult(status -> userRepository.findByUsername(username).orElseThrow());
			return dataSource.getHikariPoolMXBean().getActiveConnections();
		}
	}
}
