package com.mansereok.server.domain.interpret.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.InputInfo;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.MonthlyFortune;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.user.service.EmailService;
import com.mansereok.server.domain.user.service.UserService;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManseInterpretationServicePromptTest {

	@Mock
	private GptApiRetryService gptApiRetryService;
	@Mock
	private UserService userService;
	@Mock
	private CompatibilityResultRepository compatibilityResultRepository;
	@Mock
	private OgImageGenerationService ogImageGenerationService;
	@Mock
	private DiscordNotificationService discordNotificationService;
	@Mock
	private EmailService emailService;
	@Mock
	private SajuResultService sajuResultService;

	private ManseInterpretationService service;

	@BeforeEach
	void setUp() {
		service = new ManseInterpretationService(
			"test-api-key",
			"https://api.openai.com",
			gptApiRetryService,
			userService,
			compatibilityResultRepository,
			ogImageGenerationService,
			discordNotificationService,
			emailService,
			sajuResultService
		);
	}

	@Test
	void shouldGenerateBusinessPromptWithNewSectionStructureAndPageBreakRules() throws Exception {
		ManseryeokCalculationResponse response = sampleResponse();

		Method method = ManseInterpretationService.class.getDeclaredMethod(
			"createBusinessLuckPrompt",
			String.class,
			ManseryeokCalculationResponse.class
		);
		method.setAccessible(true);

		String prompt = (String) method.invoke(service, "김태우", response);

		assertNotNull(prompt);
		assertTrue(
			prompt.contains("fullAnalysis 총 분량은 5000~6000자 사이로 작성한다.")
				|| prompt.contains("fullAnalysis 총 분량은 5000자 이상 6000자 이하"));
		assertTrue(prompt.contains("페이지 분리는 반드시 줄바꿈 두 번(\\\\n\\\\n)으로만 한다."));
		assertTrue(prompt.contains("### 문체 기준 (골드 스탠다드) ###"));
		assertTrue(prompt.contains("### 이야기 흐름 (제목/번호는 출력하지 말 것) ###"));
		assertTrue(prompt.contains("### 절대 금지 패턴 ###"));
		assertTrue(prompt.contains("한 문단에 월 2개 이상 언급 금지"));
		assertTrue(prompt.contains("### 권장 서술 패턴 ###"));
		assertTrue(prompt.contains("yyyy년 M월 형식만 사용"));
		assertTrue(prompt.contains("한자(寅, 卯, 沖"));
		assertTrue(prompt.contains("색깔/방향/숫자 개운법"));
		assertTrue(prompt.contains("summary는 4~5줄로 작성"));
		assertTrue(prompt.contains("summary 총 길이는 280자 이내"));
		assertTrue(prompt.contains("### 월운 (향후 12개월) ###"));
		assertTrue(prompt.contains("적용구간:"));
		assertFalse(prompt.contains("## 1. 한 줄 결론"));
		assertFalse(prompt.contains("## 1. 성격 분석 + 사주적 근거"));
		assertFalse(prompt.contains("대괄호(`[]`)"));
		assertFalse(prompt.contains("첫 번째 단락 묶음"));
		assertFalse(prompt.contains("fullAnalysis 총 분량은 약 4000자 내외"));
	}

	@Test
	void shouldGenerate2026KeywordPromptWithParagraphRulesAndNoMetaOpening() throws Exception {
		ManseryeokCalculationResponse response = sampleResponse();

		Method method = ManseInterpretationService.class.getDeclaredMethod(
			"create2026KeywordPrompt",
			String.class,
			ManseryeokCalculationResponse.class
		);
		method.setAccessible(true);

		String prompt = (String) method.invoke(service, "은정", response);

		assertNotNull(prompt);
		assertTrue(prompt.contains("첫 줄은 [2026년 상반기 운명 키워드: 키워드명] 형식"));
		assertTrue(prompt.contains("정확히 4개 문단"));
		assertTrue(prompt.contains("줄바꿈 두 번(\\\\n\\\\n)"));
		assertTrue(prompt.contains("\"직접 대면 상담하듯 핵심만 전해드립니다\""));
		assertTrue(prompt.contains("번호형 나열(1-1, 첫째, 둘째)"));
		assertTrue(prompt.contains("날짜 표기는 2026년 3월처럼 년-월까지만"));
		assertFalse(prompt.contains("## 2026년 상반기 운명 키워드: [키워드 명]"));
		assertFalse(prompt.contains("목차 강제"));
	}

	@Test
	void shouldNormalizeBusinessOutputMarkersAndDatetimeFormat() throws Exception {
		Method method = ManseInterpretationService.class.getDeclaredMethod(
			"normalizeAnalysisBySubcategory",
			Long.class,
			String.class
		);
		method.setAccessible(true);

		String raw = """
			[1. 성격 분석 + 사주적 근거]
			1-1 핵심 성향은 임수 일간입니다.
			2-1 유리 구간 A는 2026-02-04T04:38~2026-04-05T03:32:59입니다.
			천간충과 양인살이 있어요.
			공망이 寅, 卯라 동쪽에서 청색을 쓰고 숫자 3과 8을 추천합니다.
			[PAGE_BREAK]
			### 다음 단락
			3) 실행 체크리스트를 작성하세요.
			""";

		String normalized = (String) method.invoke(service, 21L, raw);

		assertNotNull(normalized);
		assertNotEquals(raw, normalized);
		assertFalse(normalized.contains("[PAGE_BREAK]"));
		assertFalse(normalized.contains("[1. 성격 분석 + 사주적 근거]"));
		assertFalse(normalized.contains("1-1 "));
		assertFalse(normalized.contains("###"));
		assertFalse(normalized.contains("3) "));
		assertFalse(normalized.contains("T03:32:59"));
		assertTrue(normalized.contains("2026년 2월~2026년 4월"));
		assertFalse(normalized.contains("寅"));
		assertFalse(normalized.contains("卯"));
		assertFalse(normalized.contains("동쪽"));
		assertFalse(normalized.contains("청색"));
		assertFalse(normalized.contains("3과 8"));
		assertFalse(normalized.contains("천간충"));
		assertTrue(normalized.contains("천간 충돌"));
	}

	@Test
	void shouldNormalizeKeywordOutputAndSplitIntoMultipleParagraphs() throws Exception {
		Method method = ManseInterpretationService.class.getDeclaredMethod(
			"normalizeAnalysisBySubcategory",
			Long.class,
			String.class
		);
		method.setAccessible(true);

		String raw = """
			[2026년 상반기 운명 키워드: 균형]
			안녕하세요 은정님, 직접 대면 상담하듯 핵심만 전해드립니다. 2026-02-04T04:38~2026-04-05T03:32:59 구간에서 흐름이 바뀝니다. 속도를 조절해야 합니다. 관계에서는 경청이 중요합니다. 2026-06-06 01:09에도 재정 결정을 점검하세요. 무리한 선택은 피하는 편이 좋습니다.
			""";

		String normalized = (String) method.invoke(service, 102L, raw);

		assertNotNull(normalized);
		assertFalse(normalized.contains("직접 대면 상담하듯"));
		assertFalse(normalized.contains("핵심만 전해드립니다"));
		assertFalse(normalized.contains("T04:38"));
		assertTrue(normalized.contains("2026년 2월~2026년 4월"));
		assertTrue(normalized.contains("\n\n"));

		long paragraphCount = Arrays.stream(normalized.split("\\n\\s*\\n"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.count();
		assertTrue(paragraphCount >= 2);
	}

	@Test
	void shouldLimitBusinessSummaryToFiveLines() throws Exception {
		Method method = ManseInterpretationService.class.getDeclaredMethod(
			"normalizeSummaryBySubcategory",
			Long.class,
			String.class
		);
		method.setAccessible(true);

		String rawSummary = """
			첫째 줄
			둘째 줄
			셋째 줄
			넷째 줄
			다섯째 줄
			여섯째 줄
			일곱째 줄
			""";

		String normalized = (String) method.invoke(service, 21L, rawSummary);

		assertNotNull(normalized);
		long lineCount = Arrays.stream(normalized.split("\n"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.count();
		assertTrue(lineCount <= 5);
		assertTrue(normalized.contains("다섯째 줄"));
		assertFalse(normalized.contains("여섯째 줄"));
	}

	private ManseryeokCalculationResponse sampleResponse() {
		PillarElement yearSky = pillar("甲", "갑", "목", "식신", "양");
		PillarElement yearGround = pillar("子", "자", "수", "겁재", "양");
		PillarElement monthSky = pillar("乙", "을", "목", "상관", "음");
		PillarElement monthGround = pillar("丑", "축", "토", "정관", "음");
		PillarElement daySky = pillar("壬", "임", "수", "비견", "양");
		PillarElement dayGround = pillar("午", "오", "화", "정재", "양");
		PillarElement timeSky = pillar("丁", "정", "화", "정재", "음");
		PillarElement timeGround = pillar("酉", "유", "금", "정인", "음");

		MonthlyFortune mf1 = MonthlyFortune.builder()
			.year(2026)
			.month(3)
			.season("경칩")
			.periodStart(LocalDateTime.of(2026, 3, 5, 11, 0))
			.periodEnd(LocalDateTime.of(2026, 4, 4, 9, 59, 59))
			.monthSky(pillar("己", "기", "토", "정관", "음"))
			.monthGround(pillar("卯", "묘", "목", "상관", "음"))
			.build();
		MonthlyFortune mf2 = MonthlyFortune.builder()
			.year(2026)
			.month(4)
			.season("청명")
			.periodStart(LocalDateTime.of(2026, 4, 4, 10, 0))
			.periodEnd(LocalDateTime.of(2026, 5, 5, 7, 59, 59))
			.monthSky(pillar("庚", "경", "금", "편인", "양"))
			.monthGround(pillar("辰", "진", "토", "편관", "양"))
			.build();

		SajuInfo saju = SajuInfo.builder()
			.yearSky(yearSky)
			.yearGround(yearGround)
			.monthSky(monthSky)
			.monthGround(monthGround)
			.daySky(daySky)
			.dayGround(dayGround)
			.timeSky(timeSky)
			.timeGround(timeGround)
			.monthlyFortunes(List.of(mf1, mf2))
			.build();

		InputInfo input = InputInfo.builder()
			.solarDate(LocalDate.of(1990, 1, 1))
			.solarTime(LocalTime.of(12, 0))
			.gender("MALE")
			.isLunar(false)
			.timeUnknown(false)
			.build();

		return ManseryeokCalculationResponse.builder()
			.input(input)
			.saju(saju)
			.build();
	}

	private PillarElement pillar(String chinese, String korean, String fiveCircle, String tenStar,
		String minusPlus) {
		return PillarElement.builder()
			.chinese(chinese)
			.korean(korean)
			.fiveCircle(fiveCircle)
			.tenStar(tenStar)
			.minusPlus(minusPlus)
			.build();
	}
}
