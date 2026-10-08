package com.mansereok.server.domain.auth.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mansereok.server.domain.auth.util.JwtUtil;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.support.LocalMySqlTest;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 탈퇴한 뒤 같은 이메일로 다시 가입하면 username(이메일)은 같고 id 만 다른 새 계정이 생긴다. 탈퇴 전에 발급받아 아직 만료되지 않은
 * 액세스 토큰이 그 새 계정으로 통하지 않는지, 실제 MySQL 과 보안 필터 체인(JwtAuthenticationFilter → 진입점)으로 확인한다.
 *
 * <p>회원은 이번 실행의 runId 로 만든 이메일로 만들고, 뒤 정리에서 그 이메일의 users 행만 지운다. 탈퇴(UserService.deleteUser)가
 * 첫 회원을 지우므로 정리 시점에는 재가입한 회원 행만 남아 있다.
 */
@AutoConfigureMockMvc
class JwtAccountMismatchMySqlTest extends LocalMySqlTest {

	private static final String PROFILE_PATH = "/api/v1/users/me/profiles";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private UserService userService;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private JwtUtil jwtUtil;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String email = "rejoin-" + runId + "@example.com";

	@AfterEach
	void deleteRowsOfThisRun() {
		jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);
	}

	@Test
	@DisplayName("탈퇴 전에 발급받은 토큰은 같은 이메일로 다시 가입한 새 계정으로 통하지 않고 401 JWT_ACCOUNT_MISMATCH 를 받는다")
	void rejectsTokenOfWithdrawnAccountAfterRejoiningWithSameEmail() throws Exception {
		// given: 가입한 회원이 받은 토큰으로 내 정보를 볼 수 있다
		User first = saveEmailSignupMember("첫가입");
		String tokenBeforeWithdrawal = accessTokenOf(first);
		mockMvc.perform(get(PROFILE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenBeforeWithdrawal))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("첫가입"));

		// when: 탈퇴한 뒤 같은 이메일로 다시 가입한다. username 은 같고 id 는 다르다.
		userService.deleteUser(email);
		User rejoined = saveEmailSignupMember("재가입");
		assertThat(rejoined.getUsername()).isEqualTo(first.getUsername());
		assertThat(rejoined.getId()).isNotEqualTo(first.getId());

		// then: 탈퇴 전 토큰은 거절되고, 재가입한 계정이 새로 받은 토큰만 통한다
		mockMvc.perform(get(PROFILE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenBeforeWithdrawal))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("JWT_ACCOUNT_MISMATCH"));
		mockMvc.perform(get(PROFILE_PATH).header(HttpHeaders.AUTHORIZATION, "Bearer " + accessTokenOf(rejoined)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("재가입"));
	}

	/**
	 * 이메일 로그인(AuthController.login)이 넣는 것과 같은 클레임으로 액세스 토큰을 만든다.
	 */
	private String accessTokenOf(User user) {
		return jwtUtil.generateAccessToken(user.getUsername(), Map.of(
			"name", user.getName(),
			"role", user.getRole().getAuthority(),
			"email", user.getEmail(),
			"userId", user.getId()));
	}

	private User saveEmailSignupMember(String name) {
		return userRepository.save(User.create(email, name, "encoded-password", email, LocalDate.of(1990, 1, 1),
			Gender.FEMALE, true, true, false));
	}
}
