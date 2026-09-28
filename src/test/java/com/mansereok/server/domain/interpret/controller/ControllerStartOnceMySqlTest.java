package com.mansereok.server.domain.interpret.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.mansereok.server.domain.interpret.dto.request.ManseCompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseInterpretationRequest;
import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.exception.InterpretationAlreadyStartedException;
import com.mansereok.server.domain.interpret.prompt.PromptFixtures;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.interpret.service.ManseCalculationService;
import com.mansereok.server.domain.interpret.service.ManseInterpretationService;
import com.mansereok.server.support.ConcurrentCalls;
import com.mansereok.server.support.ConcurrentCalls.CallResult;
import com.mansereok.server.support.InterpretationMySqlTest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 같은 결제로 해석 요청이 한꺼번에 와도 컨트롤러가 비동기 해석을 한 번만 제출하는지 실제 MySQL 로 확인한다.
 *
 * <p>컨트롤러는 만세력을 계산하고, 해석 시작을 표시하고(조건부 UPDATE), 통과한 요청만 해석을 제출한다. 제출 횟수는 밖으로 나가는
 * GPT 호출 횟수라서 해석 서비스를 {@link MockitoBean} 으로 바꿔 센다. 만세력 계산도 목으로 바꿔 입력과 상관없이 같은 결과를 돌려주게
 * 한다. 이 두 목 때문에 이 클래스는 다른 해석 MySQL 테스트와 스프링 컨텍스트를 함께 쓰지 않는다.
 *
 * <p>제출한 해석에 넘긴 해석 시작 시각이 DB 에 남은 updated_at 과 같은지도 본다. 해석은 결과를 쓸 때마다 이 둘을 견주므로, 칸에
 * 담기며 값이 달라지면 모든 해석이 자기 결과를 쓰지 못한다.
 *
 * <p>행은 이번 실행의 사용자 ID 로 만들고 뒤 정리에서 그 사용자 ID 로만 지운다.
 */
class ControllerStartOnceMySqlTest extends InterpretationMySqlTest {

	// HikariCP 기본 최대 커넥션 수(10)를 넘기지 않는다.
	private static final int REQUEST_COUNT = 10;
	private static final Long SUBCATEGORY_ID = 1L;
	// 궁합 엔드포인트는 궁합 상품 번호만 받는다(아이돌 궁합).
	private static final Long COMPATIBILITY_SUBCATEGORY_ID = 7L;
	private static final String USERNAME = "start-once-user";

	@MockitoBean
	private ManseCalculationService manseCalculationService;

	@MockitoBean
	private ManseInterpretationService manseInterpretationService;

	@Autowired
	private ManseryeokController controller;

	@Autowired
	private ResultRepository resultRepository;

	@Autowired
	private CompatibilityResultRepository compatibilityResultRepository;

	// 실행마다 다른 값이라 이전 실행이 남긴 행과 사용자 ID·결제 ID(UNIQUE)가 부딪히지 않는다.
	private final String runId = UUID.randomUUID().toString().substring(0, 8);
	private final long runKey = Long.parseLong(runId, 16) * 100;
	private final Long userId = runKey;
	private final Long paymentId = runKey + 1;

	@BeforeEach
	void calculationAlwaysSucceeds() {
		given(manseCalculationService.calculate(any())).willReturn(PromptFixtures.person1());
	}

	@AfterEach
	void deleteRowsCreatedByThisRun() {
		jdbcTemplate.update("DELETE FROM results WHERE user_id = ?", userId);
		jdbcTemplate.update("DELETE FROM compatibility_results WHERE user_id = ?", userId);
	}

	@Test
	@DisplayName("같은 결제로 유료 단일 해석 요청 10개가 동시에 와도 해석은 한 번만 제출되고 나머지 9개는 409 용 예외로 끝난다")
	void paidSingleSubmitsOnce() {
		// given
		resultRepository.save(Result.createInitial(userId, paymentId, "인생 총운"));

		// when
		List<CallResult<ResponseEntity<?>>> calls = ConcurrentCalls.runAtTheSameTime(REQUEST_COUNT,
			() -> controller.interpret(SUBCATEGORY_ID, singleRequest(), USERNAME));

		// then
		assertThat(calls).filteredOn(CallResult::succeeded).as("접수된 요청").singleElement()
			.satisfies(call -> assertThat(call.value().getStatusCode()).as("응답 상태").isEqualTo(HttpStatus.ACCEPTED));
		assertThat(calls).filteredOn(call -> !call.succeeded()).as("거절된 요청").hasSize(9)
			.allSatisfy(call -> assertThat(call.error()).isInstanceOf(InterpretationAlreadyStartedException.class));
		ArgumentCaptor<LocalDateTime> startedAt = ArgumentCaptor.forClass(LocalDateTime.class);
		then(manseInterpretationService).should(times(1))
			.interpret(anyString(), any(), anyString(), eq(SUBCATEGORY_ID), eq(paymentId), startedAt.capture(), any());
		assertThat(statusOf("results")).isEqualTo("PROCESSING");
		assertThat(updatedAtOf("results")).as("해석에 넘긴 시작 시각과 DB 의 updated_at").isEqualTo(startedAt.getValue());
	}

	@Test
	@DisplayName("같은 결제로 유료 궁합 요청 10개가 동시에 와도 해석은 한 번만 제출되고 나머지 9개는 409 용 예외로 끝난다")
	void paidCompatibilitySubmitsOnce() {
		// given
		compatibilityResultRepository.save(CompatibilityResult.createInitial(userId, paymentId, "연인 궁합"));

		// when
		List<CallResult<ResponseEntity<?>>> calls = ConcurrentCalls.runAtTheSameTime(REQUEST_COUNT,
			() -> controller.analyzeCompatibility(COMPATIBILITY_SUBCATEGORY_ID, compatibilityRequest(), USERNAME));

		// then
		assertThat(calls).filteredOn(CallResult::succeeded).as("접수된 요청").singleElement()
			.satisfies(call -> assertThat(call.value().getStatusCode()).as("응답 상태").isEqualTo(HttpStatus.ACCEPTED));
		assertThat(calls).filteredOn(call -> !call.succeeded()).as("거절된 요청").hasSize(9)
			.allSatisfy(call -> assertThat(call.error()).isInstanceOf(InterpretationAlreadyStartedException.class));
		ArgumentCaptor<LocalDateTime> startedAt = ArgumentCaptor.forClass(LocalDateTime.class);
		then(manseInterpretationService).should(times(1)).analyzeCompatibilityWithSubcategory(anyString(), any(),
			anyString(), any(), eq(COMPATIBILITY_SUBCATEGORY_ID), eq(paymentId), startedAt.capture(), anyString(), any(),
			any());
		assertThat(statusOf("compatibility_results")).isEqualTo("PROCESSING");
		assertThat(updatedAtOf("compatibility_results")).as("해석에 넘긴 시작 시각과 DB 의 updated_at")
			.isEqualTo(startedAt.getValue());
	}

	private ManseInterpretationRequest singleRequest() {
		ManseInterpretationRequest request = new ManseInterpretationRequest();
		request.setName("홍길동");
		request.setSolarDate(LocalDate.of(1990, 1, 1));
		request.setSolarTime(LocalTime.of(12, 0));
		request.setGender("MALE");
		request.setIsLunar(false);
		request.setPaymentId(paymentId);
		return request;
	}

	private ManseCompatibilityAnalysisRequest compatibilityRequest() {
		ManseCompatibilityAnalysisRequest request = new ManseCompatibilityAnalysisRequest();
		request.setPerson1(person("홍길동"));
		request.setPerson2(person("김영희"));
		request.setPaymentId(paymentId);
		return request;
	}

	private static ManseCompatibilityAnalysisRequest.PersonInfo person(String name) {
		ManseCompatibilityAnalysisRequest.PersonInfo person = new ManseCompatibilityAnalysisRequest.PersonInfo();
		person.setName(name);
		person.setSolarDate(LocalDate.of(1990, 1, 1));
		person.setSolarTime(LocalTime.of(12, 0));
		person.setGender("MALE");
		person.setIsLunar(false);
		return person;
	}

	private String statusOf(String table) {
		return jdbcTemplate.queryForObject("SELECT status FROM " + table + " WHERE payment_id = ?", String.class,
			paymentId);
	}

	private LocalDateTime updatedAtOf(String table) {
		return jdbcTemplate.queryForObject("SELECT updated_at FROM " + table + " WHERE payment_id = ?",
			LocalDateTime.class, paymentId);
	}
}
