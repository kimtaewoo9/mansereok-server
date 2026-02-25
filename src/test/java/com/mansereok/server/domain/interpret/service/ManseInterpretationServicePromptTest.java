package com.mansereok.server.domain.interpret.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
		assertTrue(prompt.contains("fullAnalysis 총 분량은 3800자 이상 4300자 이하"));
		assertTrue(prompt.contains("## 1. 성격 분석 + 사주적 근거"));
		assertTrue(prompt.contains("## 6. 안정화 로드맵 + 실행 체크리스트"));
		assertTrue(prompt.contains("[PAGE_BREAK]"));
		assertTrue(prompt.contains("아이템 추천은 아래 근거 우선순위로 판정한다."));
		assertTrue(prompt.contains("### 월운 (향후 12개월) ###"));
		assertTrue(prompt.contains("적용구간:"));
		assertFalse(prompt.contains("## 1. 한 줄 결론"));
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
