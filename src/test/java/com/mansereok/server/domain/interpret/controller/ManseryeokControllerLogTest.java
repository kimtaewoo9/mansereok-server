package com.mansereok.server.domain.interpret.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.mansereok.server.domain.interpret.calculator.RelationCalculator;
import com.mansereok.server.domain.interpret.calculator.SinsalCalculator;
import com.mansereok.server.domain.interpret.calculator.UnseongCalculator;
import com.mansereok.server.domain.interpret.calculator.YongsinCalculator;
import com.mansereok.server.domain.interpret.dto.request.CompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseCompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseInterpretationRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCalculationRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCreateRequest;
import com.mansereok.server.domain.interpret.service.ManseCalculationService;
import com.mansereok.server.domain.interpret.service.ManseInterpretationService;
import com.mansereok.server.domain.interpret.service.ResultService;
import com.mansereok.server.domain.interpret.service.SajuDataService;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.service.PaymentService;
import com.mansereok.server.support.fixture.ManseTableFixture;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 해석 요청 하나가 운영 로그 수준(INFO)에서 남기는 줄에 username·생년월일·출생시각·성별이 없는지 확인한다. 대운 시작 해에서
 * 대운수를 빼면 출생 연도가 나오므로 대운 계산 줄(bigFortuneStart=, diffDays=)도 없어야 한다.
 *
 * <p>운영 설정은 com.mansereok 을 INFO 로 둔다. 컨트롤러가 username 을 찍고 같은 요청 스레드에서 계산 서비스가 생년월일과
 * 출생시각을 찍으면, 로그만으로 특정 계정의 생년월일시를 알 수 있다. 계산 서비스는 진짜를 쓰고 만세력 표는 운영 데이터를 메모리에
 * 올려 쓴다. 절입일(1990-02-04 입춘)과 야자시(23시 30분 이후) 출생을 넣어, 그 경우에만 찍히는 줄까지 지나가게 한다.
 *
 * <p>로그가 실제로 잡히는지도 함께 본다. 요청 로그의 결제 ID 가 보이지 않으면 출력을 못 잡은 것이라, 이름·날짜가 없다는 확인도
 * 믿을 수 없다.
 *
 * <p>출생시각은 초가 0 인 시각을 넣어 "09:07" 처럼 찍히게 하고, 바로 뒤에 콜론이나 숫자가 오지 않는 자리만 찾는다. 로그 줄 앞의 찍은
 * 시각("09:07:31.123")이 테스트를 돌린 시각과 우연히 겹쳐도 틀리게 실패하지 않는다.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("ManseryeokController 요청 로그")
class ManseryeokControllerLogTest {

	private static final String USERNAME = "log-check-user";
	private static final Long PAYMENT_ID = 100L;
	private static final Long FREE_PAYMENT_ID = 200L;
	private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 9, 26, 9, 0);
	private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-26T03:00:00Z"),
		ZoneId.of("Asia/Seoul"));
	// 출생시각 "09:07" 뒤에 콜론이나 숫자가 오면 로그 줄 앞의 찍은 시각이라 뺀다.
	private static final String BIRTH_TIME_0907 = "09:07(?![:\\d])";
	private static final String BIRTH_TIME_2345 = "23:45(?![:\\d])";
	private static final String BIRTH_TIME_2115 = "21:15(?![:\\d])";
	private static final Logger APP_LOGGER = (Logger) LoggerFactory.getLogger("com.mansereok");

	@Mock
	private ManseInterpretationService manseInterpretationService;
	@Mock
	private PaymentService paymentService;
	@Mock
	private ResultService resultService;

	private ManseryeokController controller;
	private Level levelBeforeTest;

	@BeforeEach
	void setUp() {
		ManseCalculationService calculationService = new ManseCalculationService(
			ManseTableFixture.realTable().newRepository(), new SajuDataService(), new UnseongCalculator(),
			new SinsalCalculator(), new RelationCalculator(), new YongsinCalculator(), FIXED_CLOCK);
		controller = new ManseryeokController(calculationService, manseInterpretationService, paymentService,
			resultService);

		// 테스트 JVM 의 로그 설정과 상관없이 운영과 같은 INFO 에서 본다.
		levelBeforeTest = APP_LOGGER.getLevel();
		APP_LOGGER.setLevel(Level.INFO);
	}

	@AfterEach
	void restoreLogLevel() {
		APP_LOGGER.setLevel(levelBeforeTest);
	}

	@Test
	@DisplayName("유료 단일 해석 요청은 절입일 출생이어도 username·생년월일·출생시각·성별·대운 시작 해를 남기지 않는다")
	void paidSingleRequestLogsNoPersonalData(CapturedOutput output) {
		// given: 1990-02-04 는 입춘 절입일이다
		given(resultService.startProcessing(PAYMENT_ID)).willReturn(STARTED_AT);

		// when
		controller.interpret(1L, singleRequest(LocalDate.of(1990, 2, 4), LocalTime.of(9, 7)), USERNAME);

		// then
		assertThat(output.getOut()).as("요청 로그가 잡혀야 아래 확인을 믿을 수 있다").contains("paymentId=100");
		assertThat(output.getOut())
			.doesNotContain(USERNAME, "1990-02-04", "gender=", "bigFortuneStart=", "diffDays=", "절입시간 이전 출생")
			.doesNotContainPattern(BIRTH_TIME_0907);
	}

	@Test
	@DisplayName("무료 단일 해석 요청은 야자시 출생이어도 username·생년월일·다음 날 날짜·출생시각·성별·대운 시작 해를 남기지 않는다")
	void freeSingleRequestLogsNoPersonalData(CapturedOutput output) {
		// given
		givenFreeOrder(101L);
		given(resultService.startProcessing(FREE_PAYMENT_ID)).willReturn(STARTED_AT);

		// when
		controller.interpretFree(101L, singleRequest(LocalDate.of(1990, 3, 24), LocalTime.of(23, 45)), USERNAME);

		// then
		assertThat(output.getOut()).as("요청 로그가 잡혀야 아래 확인을 믿을 수 있다").contains("paymentId=200");
		assertThat(output.getOut())
			.doesNotContain(USERNAME, "1990-03-24", "1990-03-25", "gender=", "bigFortuneStart=", "diffDays=")
			.doesNotContainPattern(BIRTH_TIME_2345);
	}

	@Test
	@DisplayName("유료 궁합 요청은 username·두 사람의 생년월일·출생시각·성별·대운 시작 해를 남기지 않는다")
	void paidCompatibilityRequestLogsNoPersonalData(CapturedOutput output) {
		// given
		given(resultService.startCompatibilityProcessing(PAYMENT_ID)).willReturn(STARTED_AT);
		ManseCompatibilityAnalysisRequest request = new ManseCompatibilityAnalysisRequest();
		request.setPerson1(person("홍길동", LocalDate.of(1990, 2, 4), LocalTime.of(9, 7)));
		request.setPerson2(person("김영희", LocalDate.of(1992, 11, 3), LocalTime.of(21, 15)));
		request.setPaymentId(PAYMENT_ID);

		// when
		controller.analyzeCompatibility(7L, request, USERNAME);

		// then
		assertThat(output.getOut()).as("요청 로그가 잡혀야 아래 확인을 믿을 수 있다").contains("paymentId=100");
		assertThat(output.getOut())
			.doesNotContain(USERNAME, "1990-02-04", "1992-11-03", "gender=", "bigFortuneStart=", "diffDays=")
			.doesNotContainPattern(BIRTH_TIME_0907)
			.doesNotContainPattern(BIRTH_TIME_2115);
	}

	@Test
	@DisplayName("무료 궁합 요청은 username·두 사람의 생년월일 문자열·출생시각·성별·대운 시작 해를 남기지 않는다")
	void freeCompatibilityRequestLogsNoPersonalData(CapturedOutput output) {
		// given
		givenFreeOrder(7L);
		given(resultService.startCompatibilityProcessing(FREE_PAYMENT_ID)).willReturn(STARTED_AT);
		CompatibilityAnalysisRequest request = new CompatibilityAnalysisRequest();
		request.setPerson1(freePerson("홍길동", "1990/02/04", "09:07"));
		request.setPerson2(freePerson("김영희", "1992/11/03", "21:15"));

		// when
		controller.analyzeCompatibilityFree(7L, request, USERNAME);

		// then
		assertThat(output.getOut()).as("요청 로그가 잡혀야 아래 확인을 믿을 수 있다").contains("paymentId=200");
		assertThat(output.getOut())
			.doesNotContain(USERNAME, "1990/02/04", "1990-02-04", "1992/11/03", "1992-11-03", "gender=",
				"bigFortuneStart=", "diffDays=")
			.doesNotContainPattern(BIRTH_TIME_0907)
			.doesNotContainPattern(BIRTH_TIME_2115);
	}

	@Test
	@DisplayName("/calculate 요청은 생년월일·출생시각·성별·대운 시작 해를 남기지 않는다")
	void calculateRequestLogsNoPersonalData(CapturedOutput output) {
		// given
		ManseryeokCalculationRequest request = ManseryeokCalculationRequest.builder()
			.name("홍길동").solarDate(LocalDate.of(1990, 2, 4)).solarTime(LocalTime.of(9, 7)).gender("MALE")
			.isLunar(false)
			.build();

		// when
		controller.calculate(request);

		// then
		assertThat(output.getOut()).as("요청 로그가 잡혀야 아래 확인을 믿을 수 있다").contains("만세력 계산 완료");
		assertThat(output.getOut()).doesNotContain("1990-02-04", "gender=", "bigFortuneStart=", "diffDays=")
			.doesNotContainPattern(BIRTH_TIME_0907);
	}

	private void givenFreeOrder(Long subcategoryId) {
		Payment payment = Payment.create("pay_free_log", "free_log", 0L, PaymentStatus.PAID, 1L, 1L, subcategoryId);
		ReflectionTestUtils.setField(payment, "id", FREE_PAYMENT_ID);
		given(paymentService.createFreeOrder(USERNAME, subcategoryId)).willReturn(payment);
	}

	private ManseInterpretationRequest singleRequest(LocalDate birthDate, LocalTime birthTime) {
		ManseInterpretationRequest request = new ManseInterpretationRequest();
		request.setName("홍길동");
		request.setSolarDate(birthDate);
		request.setSolarTime(birthTime);
		request.setGender("MALE");
		request.setIsLunar(false);
		request.setPaymentId(PAYMENT_ID);
		return request;
	}

	private ManseCompatibilityAnalysisRequest.PersonInfo person(String name, LocalDate birthDate,
		LocalTime birthTime) {
		ManseCompatibilityAnalysisRequest.PersonInfo person = new ManseCompatibilityAnalysisRequest.PersonInfo();
		person.setName(name);
		person.setSolarDate(birthDate);
		person.setSolarTime(birthTime);
		person.setGender("FEMALE");
		person.setIsLunar(false);
		return person;
	}

	private ManseryeokCreateRequest freePerson(String name, String birthday, String birthtime) {
		ManseryeokCreateRequest person = new ManseryeokCreateRequest();
		person.setName(name);
		person.setGender("FEMALE");
		person.setCalendar("S");
		person.setBirthday(birthday);
		person.setBirthtime(birthtime);
		return person;
	}
}
