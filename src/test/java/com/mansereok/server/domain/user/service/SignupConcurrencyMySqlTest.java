package com.mansereok.server.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.mansereok.server.domain.auth.service.oauth.OauthLoginResult;
import com.mansereok.server.domain.auth.service.oauth.OauthLoginService;
import com.mansereok.server.domain.auth.service.oauth.OauthProfile;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.global.exception.DuplicateEmailException;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.LocalMySqlTest;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

/**
 * 같은 가입이 한꺼번에 몰려도 계정이 하나만 생기고, 나머지 요청은 500 이 아니라 DuplicateEmailException(409) 이나 먼저 가입한
 * 계정으로의 로그인으로 끝나는지 실제 MySQL 로 확인한다.
 *
 * <p>existsByEmail 확인을 함께 지나친 요청 중 하나만 저장되게 막는 것은 users 의 UNIQUE 이고, 그 위반이 Hibernate·스프링을 거쳐
 * 어떤 예외가 되는지도 DB 와 방언에 달린 일이라 목으로는 확인할 수 없다. 테스트 DB 는 ddl-auto: update 로 엔티티의 UNIQUE 가
 * 걸려 있다(UserUniqueKeysMySqlTest 가 확인한다). DuplicateEmailException 이 409 가 되는 것은 GlobalExceptionHandlerBaselineTest
 * 가 본다.
 *
 * <p>모든 행은 이번 실행의 runId 를 넣은 이메일·카카오 번호로 만들고, 뒤 정리에서 그 행만 지운다.
 */
class SignupConcurrencyMySqlTest extends LocalMySqlTest {

	// HikariCP 기본 최대 커넥션 수(10)를 넘기지 않는다.
	private static final int SAME_EMAIL_SIGNUP_COUNT = 10;
	private static final int SAME_KAKAO_FIRST_LOGIN_COUNT = 5;

	@Autowired
	private UserService userService;
	@Autowired
	private OauthLoginService oauthLoginService;
	@Autowired
	private EntityManagerFactory entityManagerFactory;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String email = "signup-" + runId + "@example.com";
	private final String kakaoId = "kakao-signup-" + runId;

	@AfterEach
	void deleteUsersOfThisRun() {
		jdbcTemplate.update("DELETE FROM users WHERE email = ? OR social_id = ?", email, kakaoId);
	}

	@Test
	@DisplayName("같은 이메일 가입이 동시에 10번 오면 1번만 가입되고 9번은 DuplicateEmailException 이며, 다른 예외는 새지 않고 users 에 한 행, 가입 알림은 한 번 간다")
	void sameEmailSignupsAtTheSameTime() {
		// when
		List<CallResult<User>> results = ConcurrentCalls.runAtTheSameTime(SAME_EMAIL_SIGNUP_COUNT,
			() -> userService.createUser("동시가입", email, "password123", LocalDate.of(1990, 1, 1),
				Gender.FEMALE, true, false));

		// then: 예외를 삼키지 않고 요청마다 결과를 본다
		assertThat(results).filteredOn(CallResult::succeeded).as("가입된 요청").hasSize(1);
		assertThat(results).filteredOn(result -> !result.succeeded()).as("거절된 요청")
			.hasSize(9)
			.allSatisfy(result -> assertThat(result.error())
				.as("거절은 모두 409 가 되는 DuplicateEmailException 이어야 한다")
				.isInstanceOf(DuplicateEmailException.class)
				.hasMessage("이미 존재하는 이메일 입니다: " + email));

		// then: DB 에 남은 행은 JPA 캐시를 거치지 않고 SQL 로 센다
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE email = ?", Integer.class,
			email)).as("users 행").isEqualTo(1);

		// then: 알림은 커밋 뒤에 간다. 알림 전용 스레드 풀로 옮겨도 이 확인이 그대로 맞도록 조건이 맞을 때까지 기다린다.
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			then(discordNotificationService).should(times(1))
				.sendUserCreatedNotification(eq("동시가입"), eq(email), any(), eq("일반 회원가입"), any());
			then(slackNotificationService).should(times(1))
				.sendUserCreatedNotification(eq("동시가입"), eq(email), any(), eq("일반 회원가입"), any());
		});
	}

	@Test
	@DisplayName("같은 카카오 계정의 첫 로그인이 동시에 5번 오면 모두 같은 계정으로 로그인하고, 새로 가입한 요청은 1번이며 users 에 한 행이다")
	void sameKakaoFirstLoginsAtTheSameTime() {
		// when
		List<CallResult<OauthLoginResult>> results = ConcurrentCalls.runAtTheSameTime(
			SAME_KAKAO_FIRST_LOGIN_COUNT, () -> oauthLoginService.loginOrRegister(kakaoProfile()));

		// then
		assertAllLoggedIntoOneNewKakaoAccount(results);
	}

	@Test
	@DisplayName("open-in-view 처럼 요청 하나가 EntityManager 하나를 끝까지 쓸 때도 같은 카카오 계정의 동시 첫 로그인 5번이 모두 같은 계정으로 로그인한다")
	void sameKakaoFirstLoginsAtTheSameTimeWithEntityManagerPerRequest() {
		// when
		List<CallResult<OauthLoginResult>> results = ConcurrentCalls.runAtTheSameTime(
			SAME_KAKAO_FIRST_LOGIN_COUNT,
			() -> withEntityManagerPerRequest(() -> oauthLoginService.loginOrRegister(kakaoProfile())));

		// then
		assertAllLoggedIntoOneNewKakaoAccount(results);
	}

	/**
	 * 모든 요청이 예외 없이 DB 에 남은 카카오 계정 하나로 로그인했고, 그중 한 요청만 새로 가입했으며, 가입 알림이 한 번 갔는지 본다.
	 */
	private void assertAllLoggedIntoOneNewKakaoAccount(List<CallResult<OauthLoginResult>> results) {
		assertThat(results).hasSize(SAME_KAKAO_FIRST_LOGIN_COUNT)
			.allSatisfy(result -> assertThat(result.error()).as("요청이 예외 없이 끝나야 한다").isNull());

		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM users WHERE social_type = 'KAKAO' AND social_id = ?", Integer.class, kakaoId))
			.as("users 행").isEqualTo(1);
		Long savedUserId = jdbcTemplate.queryForObject(
			"SELECT id FROM users WHERE social_type = 'KAKAO' AND social_id = ?", Long.class, kakaoId);
		assertThat(results)
			.extracting(result -> result.value().user().getId())
			.as("모든 요청이 DB 에 남은 그 계정으로 로그인해야 한다")
			.containsOnly(savedUserId);
		assertThat(results)
			.extracting(result -> result.value().newlyRegistered())
			.as("새로 가입한 요청은 하나다")
			.containsExactlyInAnyOrder(true, false, false, false, false);

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
			then(discordNotificationService).should(times(1))
				.sendUserCreatedNotification(eq("카카오 사용자"), isNull(), eq(savedUserId), eq("KAKAO OAuth"), any()));
	}

	// 이메일 제공에 동의하지 않은 카카오 사용자. 이메일이 없으니 username(카카오 번호)과 (social_id, social_type) UNIQUE 가 중복을 막는다.
	private OauthProfile kakaoProfile() {
		return new OauthProfile(SocialType.KAKAO, kakaoId, null, "카카오 사용자", false);
	}

	/**
	 * open-in-view 가 켜진 웹 요청처럼, 작업 하나가 EntityManager 하나를 처음부터 끝까지 쓰게 한다. 스프링이 웹 요청마다 쓰는
	 * OpenEntityManagerInViewInterceptor 로 EntityManager 를 스레드에 묶었다 푼다.
	 *
	 * <p>이 조건에서는 UNIQUE 위반으로 롤백된 가입 트랜잭션과 그 뒤의 다시 찾기가 같은 EntityManager 를 쓴다. 운영은 아직
	 * open-in-view 가 켜져 있어 소셜 로그인 요청이 이렇게 돈다. 설정과 상관없이 이 조건을 만들려고 인터셉터를 빈으로 받지 않고 직접
	 * 만든다.
	 */
	private <T> T withEntityManagerPerRequest(Callable<T> task) throws Exception {
		OpenEntityManagerInViewInterceptor interceptor = new OpenEntityManagerInViewInterceptor();
		interceptor.setEntityManagerFactory(entityManagerFactory);
		WebRequest request = new ServletWebRequest(new MockHttpServletRequest());
		interceptor.preHandle(request);
		try {
			return task.call();
		} finally {
			interceptor.afterCompletion(request, null);
		}
	}
}
