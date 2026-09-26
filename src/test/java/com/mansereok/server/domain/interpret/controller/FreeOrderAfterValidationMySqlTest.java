package com.mansereok.server.domain.interpret.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mansereok.server.domain.interpret.dto.request.CompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseInterpretationRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCreateRequest;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.support.InterpretationMySqlTest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 무료 해석 요청의 입력이 잘못되면 0원 주문·결제·결과 행이 하나도 남지 않는지 실제 MySQL 로 확인한다.
 *
 * <p>0원 주문 생성(createFreeOrder)은 주문·결제·첫 결과 행을 커밋하고 되돌리지 않는다. 컨트롤러가 입력 변환과 만세력 계산을 주문
 * 생성보다 먼저 끝내야, 잘못된 입력으로 재시도할 때마다 "정보 입력 대기" 결제가 쌓이지 않는다. 사용자와 상품 행을 미리 넣어 두어
 * 순서가 거꾸로면 주문 생성이 성공해 행이 남게 한다.
 *
 * <p>컨트롤러·계산 서비스·결제 서비스는 모두 진짜다. 계산은 이 스키마의 manses 기초 데이터(1900~2100년)를 읽는다. 해석 서비스는
 * 진짜지만 입력 단계에서 끝나므로 불리지 않는다.
 *
 * <p>행은 이번 실행의 사용자로만 만들고 뒤 정리에서 그 사용자 ID 로만 지운다. 상품 행은 번호를 정해 넣고 뒤 정리에서 그 번호로 지운다.
 */
class FreeOrderAfterValidationMySqlTest extends InterpretationMySqlTest {

	// 다른 해석 MySQL 테스트가 쓰지 않는 번호다. 무료 궁합은 궁합 상품, 무료 단일은 무료 운세 상품만 받는다.
	private static final long FREE_COMPATIBILITY_PRODUCT_ID = 15L;
	private static final long FREE_FORTUNE_PRODUCT_ID = 106L;

	@Autowired
	private ManseryeokController controller;

	@Autowired
	private UserRepository userRepository;

	// 실행마다 다른 값이라 이전 실행이 남긴 사용자와 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final String username = "free_order_" + runId;

	private Long userId;

	@BeforeEach
	void createUserAndProducts() {
		userId = userRepository.save(User.create(username, "무료 주문", "password", username + "@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, false)).getId();
		insertProduct(FREE_COMPATIBILITY_PRODUCT_ID, "무료 궁합 테스트 상품");
		insertProduct(FREE_FORTUNE_PRODUCT_ID, "무료 운세 테스트 상품");
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		jdbcTemplate.update("DELETE FROM results WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM compatibility_results WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM payments WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM subcategories WHERE id IN (?, ?)", FREE_COMPATIBILITY_PRODUCT_ID,
			FREE_FORTUNE_PRODUCT_ID);
		userRepository.deleteById(userId);
	}

	@Test
	@DisplayName("무료 궁합에서 두 번째 사람의 생년월일이 없는 날짜면 400 용 예외로 끝나고 주문·결제·결과 행이 남지 않는다")
	void impossibleBirthdayLeavesNoFreeOrder() {
		// given
		CompatibilityAnalysisRequest request = freeCompatibilityRequest("1990/13/01");

		// when & then
		assertThatThrownBy(() -> controller.analyzeCompatibilityFree(FREE_COMPATIBILITY_PRODUCT_ID, request, username))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("생년월일은 YYYY/MM/DD 형식의 있는 날짜여야 합니다.");
		assertNoRowsForThisUser();
	}

	@Test
	@DisplayName("무료 궁합에서 두 번째 사람의 생년월일이 비어 있으면 400 용 예외로 끝나고 주문·결제·결과 행이 남지 않는다")
	void missingBirthdayLeavesNoFreeOrder() {
		// given
		CompatibilityAnalysisRequest request = freeCompatibilityRequest(null);

		// when & then
		assertThatThrownBy(() -> controller.analyzeCompatibilityFree(FREE_COMPATIBILITY_PRODUCT_ID, request, username))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("생년월일(birthday)은 필수입니다.");
		assertNoRowsForThisUser();
	}

	@Test
	@DisplayName("무료 단일에서 만세력 표 범위 밖 날짜라 계산이 실패하면 주문·결제·결과 행이 남지 않는다")
	void calculationFailureLeavesNoFreeOrder() {
		// given: manses 는 1900-01-01 부터 있다
		ManseInterpretationRequest request = new ManseInterpretationRequest("홍길동", LocalDate.of(1899, 12, 31),
			LocalTime.of(12, 0), "MALE", false, null, null, null);

		// when & then
		assertThatThrownBy(() -> controller.interpretFree(FREE_FORTUNE_PRODUCT_ID, request, username))
			.hasMessageContaining("만세력 데이터를 찾을 수 없습니다");
		assertNoRowsForThisUser();
	}

	private void assertNoRowsForThisUser() {
		assertThat(countRows("orders")).as("주문 행").isZero();
		assertThat(countRows("payments")).as("결제 행").isZero();
		assertThat(countRows("results")).as("사주 결과 행").isZero();
		assertThat(countRows("compatibility_results")).as("궁합 결과 행").isZero();
	}

	private int countRows(String table) {
		Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id = ?",
			Integer.class, userId);
		return count == null ? 0 : count;
	}

	/**
	 * 상품 번호를 정해 0원 상품 행을 넣는다. 컨트롤러는 경로의 번호로 상품 종류를 가리고 0원 주문도 그 번호로 만들므로, 자동 증가 값에
	 * 맡기지 않는다.
	 */
	private void insertProduct(long productId, String title) {
		assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM subcategories WHERE id = ?", Integer.class,
			productId))
			.as("상품 %d 가 이미 있다. 이전 실행이 남긴 테스트 상품이면 지우고 다시 돌린다", productId)
			.isZero();
		jdbcTemplate.update("INSERT INTO subcategories (id, title, price) VALUES (?, ?, 0)", productId, title);
	}

	private CompatibilityAnalysisRequest freeCompatibilityRequest(String person2Birthday) {
		CompatibilityAnalysisRequest request = new CompatibilityAnalysisRequest();
		request.setPerson1(freePerson("홍길동", "1990/01/01"));
		request.setPerson2(freePerson("김영희", person2Birthday));
		return request;
	}

	private static ManseryeokCreateRequest freePerson(String name, String birthday) {
		ManseryeokCreateRequest person = new ManseryeokCreateRequest();
		person.setName(name);
		person.setGender("FEMALE");
		person.setCalendar("S");
		person.setBirthday(birthday);
		person.setBirthtime("12:00");
		return person;
	}
}
