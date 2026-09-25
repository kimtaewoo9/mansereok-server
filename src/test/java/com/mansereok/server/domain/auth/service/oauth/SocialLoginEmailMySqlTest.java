package com.mansereok.server.domain.auth.service.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.auth.dto.response.oauth.KakaoProfileDto;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.LocalMySqlTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 이메일 없는 소셜 계정과 제공자 구분을 실제 MySQL 에서 확인한다.
 *
 * <p>Spring Data 파생 쿼리가 null 인자를 "email IS NULL" 로 바꾸는 동작과 (social_type, social_id) 조회 조건은 쿼리가 DB 에서
 * 어떻게 도는지의 문제라 목으로는 확인할 수 없다.
 *
 * <p>모든 행은 이번 실행의 runId 를 social_id 끝에 붙여 만들고, 뒤 정리에서 그 행만 지운다.
 */
class SocialLoginEmailMySqlTest extends LocalMySqlTest {

	// HikariCP 기본 최대 커넥션 수(10)를 넘기지 않는다.
	private static final int FIRST_LOGIN_COUNT = 10;

	@Autowired
	private OauthLoginService oauthLoginService;
	@Autowired
	private UserRepository userRepository;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);

	// 이메일 없이 먼저 가입해 둔 카카오 회원. 예전 코드에서는 이메일 없는 다음 사람들이 모두 이 계정으로 들어갔다.
	private User userWithoutEmail;

	@BeforeEach
	void saveUserWithoutEmail() {
		userWithoutEmail = userRepository.save(User.createByOauth(socialId("first"), "카카오 사용자",
			null, socialId("first"), SocialType.KAKAO));
	}

	@AfterEach
	void deleteUsersOfThisRun() {
		jdbcTemplate.update("DELETE FROM users WHERE social_id LIKE ?", "%-" + runId);
	}

	@Test
	@DisplayName("이메일이 비어 있는 회원이 있어도 findByEmail(null) 은 아무 계정도 돌려주지 않는다")
	void findByNullEmailReturnsNothing() {
		assertThat(userRepository.findByEmail(null)).isEmpty();
	}

	@Test
	@DisplayName("이메일 없는 서로 다른 카카오 사용자 10명이 동시에 처음 로그인하면 10명 모두 자기 새 계정을 받는다")
	void kakaoUsersWithoutEmailEachGetTheirOwnAccount() {
		// when
		List<CallResult<OauthLoginResult>> results = ConcurrentCalls.runAtTheSameTime(
			FIRST_LOGIN_COUNT,
			index -> () -> oauthLoginService.loginOrRegister(
				kakaoProfileWithoutEmail(socialId("kakao" + index)).toOauthProfile()));

		// then: 예외를 삼키지 않고 요청마다 결과를 본다
		assertThat(results).hasSize(FIRST_LOGIN_COUNT).allSatisfy(result -> {
			assertThat(result.error()).as("요청이 예외 없이 끝나야 한다").isNull();
			assertThat(result.value().newlyRegistered()).as("새로 가입해야 한다").isTrue();
		});
		assertThat(results)
			.extracting(result -> result.value().user().getSocialId())
			.as("요청한 사람 자신의 카카오 번호로 된 계정을 받아야 한다")
			.containsExactly(socialId("kakao0"), socialId("kakao1"), socialId("kakao2"),
				socialId("kakao3"), socialId("kakao4"), socialId("kakao5"), socialId("kakao6"),
				socialId("kakao7"), socialId("kakao8"), socialId("kakao9"));
		assertThat(results)
			.extracting(result -> result.value().user().getId())
			.as("서로 다른 계정이고, 먼저 가입한 이메일 없는 회원의 계정이 아니어야 한다")
			.doesNotHaveDuplicates()
			.doesNotContain(userWithoutEmail.getId());

		// then: DB 에 남은 행은 JPA 캐시를 거치지 않고 SQL 로 센다
		assertThat(jdbcTemplate.queryForObject(
			"SELECT COUNT(*) FROM users WHERE social_type = 'KAKAO' AND email IS NULL"
				+ " AND social_id LIKE ?", Integer.class, "kakao%-" + runId))
			.isEqualTo(FIRST_LOGIN_COUNT);
	}

	@Test
	@DisplayName("X 에 같은 사용자 번호로 가입한 회원이 있어도 카카오 로그인은 그 계정이 아닌 새 카카오 계정으로 들어간다")
	void sameNumberOnAnotherProviderIsAnotherAccount() {
		// given
		String sharedNumber = socialId("1234567890");
		User xUser = userRepository.save(User.createByOauth("x-user-" + runId, "X 사용자", null,
			sharedNumber, SocialType.X));

		// when
		OauthLoginResult result = oauthLoginService.loginOrRegister(
			kakaoProfileWithoutEmail(sharedNumber).toOauthProfile());

		// then
		assertThat(result.newlyRegistered()).isTrue();
		assertThat(result.user().getId()).isNotEqualTo(xUser.getId());
		assertThat(result.user().getSocialType()).isEqualTo(SocialType.KAKAO);
	}

	private String socialId(String label) {
		return label + "-" + runId;
	}

	// 사용자가 이메일 제공에 동의하지 않아 kakao_account 가 없는 카카오 응답
	private static KakaoProfileDto kakaoProfileWithoutEmail(String kakaoId) {
		KakaoProfileDto kakaoProfileDto = new KakaoProfileDto();
		kakaoProfileDto.setId(kakaoId);
		return kakaoProfileDto;
	}
}
