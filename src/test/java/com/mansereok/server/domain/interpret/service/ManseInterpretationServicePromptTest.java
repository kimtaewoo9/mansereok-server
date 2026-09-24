package com.mansereok.server.domain.interpret.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.interpret.client.TestOpenAiProperties;
import com.mansereok.server.domain.interpret.client.OpenAiResponsesClient;
import com.mansereok.server.domain.interpret.prompt.CompatibilityPromptFactory;
import com.mansereok.server.domain.interpret.prompt.SajuPromptFactory;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.user.service.EmailService;
import com.mansereok.server.domain.user.service.UserService;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManseInterpretationServicePromptTest {

	@Mock
	private OpenAiResponsesClient openAiResponsesClient;
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
			new ObjectMapper(),
			openAiResponsesClient,
			TestOpenAiProperties.defaults(),
			userService,
			compatibilityResultRepository,
			ogImageGenerationService,
			discordNotificationService,
			emailService,
			sajuResultService,
			new SajuPromptFactory(),
			new CompatibilityPromptFactory()
		);
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
	void shouldNormalizeMoneyLuckOutputAndRemoveCorruptedUnicode() throws Exception {
		Method method = ManseInterpretationService.class.getDeclaredMethod(
			"normalizeAnalysisBySubcategory",
			Long.class,
			String.class
		);
		method.setAccessible(true);

		String raw = """
			[A. 돈벼락 가능성]
			인생의 판이 바뀔 수 있습니다.
			جذب力이 강하게 작동합니다.
			[주의할 점과
			조언]
			2026-02-04T04:38~2026-04-05T03:32:59에 흐름이 바뀝니다.
			""";

		String normalized = (String) method.invoke(service, 20L, raw);

		assertNotNull(normalized);
		assertFalse(normalized.contains("[A. 돈벼락 가능성]"));
		assertFalse(normalized.contains("جذب"));
		assertTrue(normalized.contains("흡인력"));
		assertFalse(normalized.contains("[주의할 점과"));
		assertFalse(normalized.contains("조언]"));
		assertTrue(normalized.contains("2026년 2월~2026년 4월"));
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

		long paragraphCount = Arrays.stream(normalized.split("\\n\\s*\\n"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.count();
		assertTrue(paragraphCount >= 1);
	}

	@Test
	void shouldNormalizeChemistryOutputAndSplitIntoMultipleParagraphs() throws Exception {
		Method method = ManseInterpretationService.class.getDeclaredMethod(
			"normalizeAnalysisBySubcategory",
			Long.class,
			String.class
		);
		method.setAccessible(true);

		String raw = """
			[아이돌 추천] 병화 기운이 강한 분에게는 감정의 온도를 조절해주는 유형이 잘 맞습니다. 블랙핑크의 지수는 안정적인 정서 리듬을 만들어 주는 점이 강점입니다. 다만 감정표현 속도 차이는 주의가 필요합니다. 실전 포인트는 갈등 시 결론보다 감정 확인을 먼저 하는 방식입니다. [배우 추천] 배우 박보영은 부드러운 공감력으로 감정 파고를 낮춰주는 타입입니다. 다만 의존도가 높아지면 주도권 균형이 깨질 수 있습니다. 실전 포인트는 역할을 미리 합의하는 방식입니다.
			""";

		String normalized = (String) method.invoke(service, 104L, raw);

		assertNotNull(normalized);
		assertFalse(normalized.contains("[아이돌 추천]"));
		assertFalse(normalized.contains("[배우 추천]"));
		assertTrue(normalized.contains("\n\n"));

		long paragraphCount = Arrays.stream(normalized.split("\\n\\s*\\n"))
			.map(String::trim)
			.filter(line -> !line.isEmpty())
			.count();
		assertTrue(paragraphCount >= 3);
	}

	@Test
	void shouldNormalizeAllFreeFortunesWithContextAwareParagraphs() throws Exception {
		Method method = ManseInterpretationService.class.getDeclaredMethod(
			"normalizeAnalysisBySubcategory",
			Long.class,
			String.class
		);
		method.setAccessible(true);

		String raw101 = """
			환경의 변화에서 이동수가 강하게 들어오며 일과 생활 리듬이 바뀔 가능성이 큽니다. 인간관계의 변화에서는 새로운 협업 제안이 들어오지만 조율이 필요합니다. 연애와 애정운은 감정 기복을 관리하면 안정적으로 이어질 흐름입니다. 학업 및 성취운은 집중력만 유지하면 성과가 확실히 보이는 구간입니다. 건강 및 컨디션은 수면과 회복 루틴을 먼저 잡아야 낙폭이 줄어듭니다.
			""";

		String raw103 = """
			당신의 매력 포인트는 무대 장악력과 솔직한 에너지입니다. 나만의 플러팅 비법은 과한 설명보다 짧고 정확한 표현으로 상대의 반응을 끌어내는 방식입니다. 이것만은 주의하세요 감정이 올라왔을 때 말의 강도가 높아지면 오해가 빠르게 커질 수 있습니다.
			""";

		String raw105 = """
			오늘의 총운은 속도를 낮추고 우선순위를 정리할수록 결과가 좋아지는 흐름입니다. 재물운에서는 즉흥 결제보다 검토 후 지출이 안정성을 높입니다. 애정운은 경청이 핵심이며 성취운은 작은 마감부터 끝내는 방식이 효율을 올립니다.
			""";

		String normalized101 = (String) method.invoke(service, 101L, raw101);
		String normalized103 = (String) method.invoke(service, 103L, raw103);
		String normalized105 = (String) method.invoke(service, 105L, raw105);

		assertTrue(Arrays.stream(normalized101.split("\\n\\s*\\n"))
			.map(String::trim).filter(line -> !line.isEmpty()).count() >= 3);
		assertTrue(Arrays.stream(normalized103.split("\\n\\s*\\n"))
			.map(String::trim).filter(line -> !line.isEmpty()).count() >= 3);
		assertTrue(Arrays.stream(normalized105.split("\\n\\s*\\n"))
			.map(String::trim).filter(line -> !line.isEmpty()).count() >= 3);
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

}
