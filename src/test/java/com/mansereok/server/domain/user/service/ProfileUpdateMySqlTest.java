package com.mansereok.server.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mansereok.server.domain.user.dto.request.ProfileUpdateRequestDto;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.support.LocalMySqlTest;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 프로필 수정이 users 행에 실제로 남는지 실제 MySQL 로 확인한다.
 *
 * <p>updateUserProfile 은 save 를 부르지 않고 쓰기 트랜잭션의 변경 감지로만 저장한다. 그래서 @Transactional 을 지우거나 readOnly 로
 * 바꾸면, 돌려준 User 에는 바뀐 값이 보이는데 DB 에는 남지 않는다. 목을 쓰는 단위 테스트와 standalone MockMvc 테스트는 이 차이를 볼
 * 수 없어, JPA 캐시를 거치지 않는 JdbcTemplate 으로 users 행을 직접 읽는다.
 *
 * <p>회원은 이번 실행의 runId 를 넣은 이메일로 만들고, 뒤 정리에서 그 행만 지운다.
 */
class ProfileUpdateMySqlTest extends LocalMySqlTest {

	@Autowired
	private UserService userService;
	@Autowired
	private UserRepository userRepository;

	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String email = "profile-" + runId + "@example.com";

	private Long userId;

	@BeforeEach
	void saveEmailSignupMember() {
		userId = userRepository.save(User.create(email, "기존이름", "encoded-password", email,
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false)).getId();
	}

	@AfterEach
	void deleteRowsOfThisRun() {
		jdbcTemplate.update("DELETE FROM users WHERE id = ?", userId);
	}

	@Test
	@DisplayName("프로필을 바꾸면 users 행의 이름·태어난 장소(앞뒤 공백을 뗀 값)·성별이 바뀐다")
	void savesChangedProfileToUsersRow() {
		// given
		ProfileUpdateRequestDto request = new ProfileUpdateRequestDto();
		request.setName("새이름");
		request.setBirthPlace("  서울 강남구  ");
		request.setGender(Gender.FEMALE);

		// when
		userService.updateUserProfile(email, request);

		// then
		Map<String, Object> row = jdbcTemplate.queryForMap(
			"SELECT name, birth_place, gender FROM users WHERE id = ?", userId);
		assertThat(row).containsExactlyInAnyOrderEntriesOf(Map.of(
			"name", "새이름",
			"birth_place", "서울 강남구",
			"gender", "FEMALE"));
	}
}
