package com.mansereok.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.entity.CompatibilityResult;
import com.mansereok.server.entity.Result;
import com.mansereok.server.repository.CompatibilityResultRepository;
import com.mansereok.server.repository.ResultRepository;
import com.mansereok.server.service.request.Gpt5Request;
import com.mansereok.server.service.response.ManseCompatibilityAnalysisResponse;
import com.mansereok.server.service.response.ManseInterpretationResponse;
import com.mansereok.server.service.response.ManseryeokCalculationResponse;
import com.mansereok.server.service.response.ManseryeokCalculationResponse.JijangganElement;
import com.mansereok.server.service.response.ManseryeokCalculationResponse.JijangganInfo;
import com.mansereok.server.service.response.ManseryeokCalculationResponse.PillarElement;
import com.mansereok.server.service.response.model.GptCompatibilityResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
@Slf4j
public class ManseInterpretationService {

	private final RestClient restClient;
	private final ObjectMapper objectMapper = new ObjectMapper();

	private final ResultRepository resultRepository;
	private final CompatibilityResultRepository compatibilityResultRepository;

	// 60갑자 순서 정의 (대운 계산용)
	private static final List<String> HEAVENLY_STEMS = Arrays.asList("甲", "乙", "丙", "丁", "戊",
		"己", "庚", "辛", "壬", "癸");
	private static final List<String> EARTHLY_BRANCHES = Arrays.asList("子", "丑", "寅", "卯", "辰",
		"巳", "午", "未", "申", "酉", "戌", "亥");
	private static final List<String> GAPJA_CYCLE = new ArrayList<>();

	static {
		for (int i = 0; i < 60; i++) {
			GAPJA_CYCLE.add(HEAVENLY_STEMS.get(i % 10) + EARTHLY_BRANCHES.get(i % 12));
		}
	}

	private static final String GPT5_SYSTEM_INSTRUCTION =
		"--- SYSTEM INSTRUCTION ---\n" +
			"당신은 30년 경력의 전문 사주명리학자입니다. 주어진 사주팔자 정보를 바탕으로 정확하고 건설적인 해석을 제공해주세요. " +
			"부정적인 내용도 포함하되 극복 방안을 함께 제시하고, 운명론적이기보다는 개인의 노력과 선택의 중요성을 강조해주세요. " +
			"'해요'체를 사용하여 부드럽고 친근한 말투를 사용해주세요.\n\n" +
			"--- USER QUERY ---\n";


	public ManseInterpretationService(@Value("${openai.api.key}") String apiKey,
		@Value("${openai.api.base-url:https://api.openai.com}") String baseUrl,
		ResultRepository resultRepository,
		CompatibilityResultRepository compatibilityResultRepository
	) {
		this.restClient = RestClient.builder()
			.baseUrl(baseUrl + "/v1")
			.defaultHeader("Authorization", "Bearer " + apiKey)
			.defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.build();
		this.resultRepository = resultRepository;
		this.compatibilityResultRepository = compatibilityResultRepository;
	}

	/**
	 * 만세력 계산 결과를 바탕으로 GPT-5에게 사주 해석을 요청하고, 구조화된 응답 객체(ManseInterpretationResponse)를 반환합니다.
	 *
	 * @param name     분석 대상자의 이름
	 * @param response 만세력 계산 결과
	 * @return GPT-5 해석과 추가 정보가 담긴 응답 DTO
	 */
	public ManseInterpretationResponse interpret(String name,
		ManseryeokCalculationResponse response) {
		log.info("✅ 사주 해석 요청 시작, 요청자: {}", name);

		// 사주의 핵심인 '일간' 정보를 미리 추출합니다.
		String ilgan = "정보 없음";
		if (response != null && response.getSaju() != null
			&& response.getSaju().getDaySky() != null) {
			PillarElement daySky = response.getSaju().getDaySky();
			ilgan = daySky.getKorean() + daySky.getFiveCircle(); // 예: "임" + "수" -> "임수"
		}

		try {
			String userPrompt = createComprehensiveAnalysisPrompt(name, response);
			String input = GPT5_SYSTEM_INSTRUCTION + userPrompt;

			Gpt5Request gpt5Request = new Gpt5Request(
				"gpt-5",
				input,
				16384,
				"high",
				"high"
			);

			String requestBody = objectMapper.writeValueAsString(gpt5Request);

			log.info("GPT-5 요청 데이터 생성 완료. API 호출 시작...");
			String gptResponse = restClient.post()
				.uri("/responses")
				.body(requestBody)
				.retrieve()
				.body(String.class);

			log.info("GPT-5 응답 수신 완료.");
			String interpretationText = extractContentFromResponseGpt5(gptResponse);

			Result savedResult = resultRepository.save(
				Result.create(
					null,  // userId - 로그인 기능 없으므로 null
					name,
					response.getInput().getSolarDate(),
					response.getInput().getSolarTime(),
					response.getInput().getGender(),
					response.getInput().getIsLunar(),
					ilgan,
					interpretationText
				)
			);

			// 성공 시, 모든 정보를 담아 DTO를 빌드하여 반환합니다.
			return new ManseInterpretationResponse(
				savedResult.getId(),
				name,
				ilgan,
				interpretationText
			);

		} catch (Exception e) {
			log.error("GPT API 요청 중 오류 발생: {}", e.getMessage(), e);

			return new ManseInterpretationResponse(
				null,
				name,
				ilgan,
				"해석 생성 중 API 요청 오류가 발생했습니다. 서버 로그를 확인해주세요."
			);
		}
	}

	/**
	 * ManseryeokCalculationResponse 객체를 바탕으로 GPT에게 전달할 프롬프트를 생성 (데이터 보강 버전)
	 */
	private String createComprehensiveAnalysisPrompt(String name,
		ManseryeokCalculationResponse response) {
		StringBuilder prompt = new StringBuilder();
		ManseryeokCalculationResponse.SajuInfo saju = response.getSaju();
		ManseryeokCalculationResponse.InputInfo input = response.getInput();

		// =================================================================
		// 1. 시스템 역할 정의 강화: '공감'과 '비유'를 핵심으로!
		// =================================================================
		prompt.append("### 시스템 역할 정의 (Role Definition) ###\n");
		prompt.append("당신은 30년 이상의 경력을 가진 대한민국 최고의 사주명리학 대가이자, 따뜻한 마음을 가진 인생 상담가 '혜안(慧眼)'입니다.\n");
		prompt.append("당신의 분석은 단순한 정보 나열이 아닌, 한 사람의 인생 서사를 깊이 공감하고 아름다운 비유로 풀어내는 예술적 컨설팅입니다.\n");
		prompt.append("아래 원칙을 '반드시' 준수하여, 유료 결제가 아깝지 않은 최고 수준의 감동적인 분석을 제공하세요:\n\n");
		prompt.append(
			"1.  **감성적 비유 활용**: 모든 명리학적 요소를 자연물(나무, 태양, 바다 등), 사물, 상황에 빗대어 consult가 자신의 삶을 한 편의 이야기처럼 느낄 수 있게 설명해주세요. (예: '임수(壬水) 일간은 드넓은 바다와 같아서...', '제왕(帝旺)은 인생의 정오에 뜬 태양과 같습니다.')\n");
		prompt.append(
			"2.  **스토리텔링**: 각 사주 기둥(년주, 월주, 일주, 시주)을 '인생의 사계절(봄, 여름, 가을, 겨울)'에 비유하여 시간의 흐름에 따른 변화를 서사적으로 풀어주세요.\n");
		prompt.append(
			"3.  **따뜻한 공감의 언어**: '~군요', '~네요', '~인 것 같아요' 와 같은 부드러운 '해요체'를 사용하여, 마치 마주 앉아 대화하듯 친근하고 따뜻하게 조언해주세요.\n");
		prompt.append(
			"4.  **지장간(地藏干)은 '숨겨진 보물'**: 지장간을 '내 안의 숨겨진 보물 상자'나 '잠재력의 씨앗'에 비유하여, 겉으로 드러나지 않는 깊은 내면의 재능과 욕구를 섬세하게 분석해주세요. 이것이 당신의 핵심 차별점입니다.\n");
		prompt.append(
			"5.  **12운성(十二運星)은 '인생의 에너지 파도'**: 12운성을 인생의 에너지 흐름을 보여주는 '파도'에 비유하여, 지금이 에너지가 차오르는 시기인지, 잠시 쉬어가야 할 시기인지 역동적으로 설명해주세요.\n");
		prompt.append(
			"6.  **균형 잡힌 희망의 메시지**: 어려운 부분(흉살, 충 등)은 '성장을 위한 과제'나 '조심해서 다뤄야 할 강력한 도구'로 비유하며, 운명에 갇히기보다 스스로 삶을 개척해나갈 수 있다는 희망과 용기를 주는 방향으로 마무리해주세요.\n\n");

		// =================================================================
		// 2. 분석 대상자 데이터 상세화 (기존과 동일)
		// =================================================================
		prompt.append("### 분석 대상자 상세 정보 ###\n");
		prompt.append(String.format("- 이름: %s\n", name));
		prompt.append(
			String.format("- 성별: %s\n", "MALE".equalsIgnoreCase(input.getGender()) ? "남자" : "여자"));
		prompt.append(
			String.format("- 생년월일시(양력): %s %s\n", input.getSolarDate(), input.getSolarTime()));

		String sajuPalja = String.format("시 일 월 년\n%s %s %s %s (천간)\n%s %s %s %s (지지)",
			saju.getTimeSky() != null ? saju.getTimeSky().getKorean() : " ",
			saju.getDaySky().getKorean(), saju.getMonthSky().getKorean(),
			saju.getYearSky().getKorean(),
			saju.getTimeGround() != null ? saju.getTimeGround().getKorean() : " ",
			saju.getDayGround().getKorean(), saju.getMonthGround().getKorean(),
			saju.getYearGround().getKorean()
		);
		prompt.append(String.format("- 사주명식:\n%s\n\n", sajuPalja));

		prompt.append("### 사주 원국(原局) 상세 데이터 ###\n");
		appendDetailedPillarInfo(prompt, "년주(年柱)", "초년운, 조상, 배경", saju.getYearSky(),
			saju.getYearGround());
		appendDetailedPillarInfo(prompt, "월주(月柱)", "청년운, 사회, 직업", saju.getMonthSky(),
			saju.getMonthGround());
		appendDetailedPillarInfo(prompt, "일주(日柱)", "중년운, 본인, 배우자", saju.getDaySky(),
			saju.getDayGround());
		if (saju.getTimeSky() != null && saju.getTimeGround() != null) {
			appendDetailedPillarInfo(prompt, "시주(時柱)", "말년운, 자녀, 결과", saju.getTimeSky(),
				saju.getTimeGround());
		}
		prompt.append("\n");

		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();
		calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);

		prompt.append("### 오행(五行) 세력 분석 (지장간 가중치 적용) ###\n");
		ohaengCounts.forEach((key, value) -> prompt.append(
			String.format("- %s: %.1f점%s\n", key, value,
				saju.getDaySky().getFiveCircle().equals(key) ? " (일간)" : "")));
		prompt.append("\n");

		prompt.append("### 십성(十星) 분포 분석 ###\n");
		sipseongCounts.forEach(
			(key, value) -> prompt.append(String.format("- %s: %d개\n", key, value)));
		prompt.append("\n");

		prompt.append("### 주요 신살(神殺) 및 공망(空亡) 정보 ###\n");
		appendSinsalAnalysis(prompt, saju);
		if (saju.getGongmang() != null && !saju.getGongmang().isEmpty()) {
			prompt.append(String.format("- 공망: %s\n", String.join(", ", saju.getGongmang())));
		}
		prompt.append("\n");

		prompt.append("### 대운(大運) 흐름 데이터 ###\n");
		prompt.append(String.format("- 대운수: %d\n", saju.getBigFortuneNumber()));
		appendDaewoonFlow(prompt, saju, input.getGender());
		prompt.append("\n");

		// =================================================================
		// 3. 분석 요청사항: '비유'와 '이야기' 강조
		// =================================================================
		prompt.append("### 종합 심층 분석 요청 ###\n\n");
		prompt.append(
			"혜안 선생님, 위 데이터를 바탕으로 아래 목차에 따라 한 편의 인생 서사시를 들려주듯 사주 분석을 시작해주세요. 각 항목마다 감성적인 비유와 따뜻한 조언을 담아 깊이 있는 해석을 부탁드립니다.\n\n");
		prompt.append("【분석 시작】\n");
		prompt.append(String.format(
			"먼저 \"%s님은 %s %s에 태어나신, 마치 [일간에 대한 핵심 비유, 예: 거침없는 바다]와 같은 분이시군요.\"로 운을 떼며 부드럽게 시작해주세요.\n\n",
			name, input.getSolarDate(), input.getSolarTime()));

		prompt.append("## 1. 사주 명반: 나의 인생 지도\n");
		prompt.append(
			"- 사주팔자 여덟 글자를 '인생 지도'에 비유하여, 각 글자가 나의 여정에서 어떤 나침반, 보물, 장애물 역할을 하는지 개괄적으로 설명해주세요.\n\n");

		prompt.append("## 2. 내가 아는 내 모습: 타고난 성향과 기질\n");
		prompt.append(
			"- 일간(日干)을 자연물에 빗대어 본질적인 성격, 강점과 약점을 이야기처럼 풀어주세요. (예: '갑목은 하늘을 향해 뻗어가는 큰 나무처럼...')\n");
		prompt.append("- 월지(月支)를 '내가 자라난 토양'에 비유하여, 나의 성격 형성에 어떤 영향을 미쳤는지 설명해주세요.\n");
		prompt.append(
			"- **[핵심]** 일지(日支)의 12운성과 지장간을 '내 마음 속 깊은 방'에 비유하며, 그 안에 숨겨진 나의 진짜 욕망과 잠재력을 섬세하게 그려주세요.\n\n");

		prompt.append("## 3. 남이 보는 내 모습: 사회적 성향과 인간관계\n");
		prompt.append(
			"- 사회궁(월주)과 대외적인 모습(년주)을 통해 타인에게 비치는 나의 이미지를 '내가 쓰고 있는 가면' 혹은 '나의 사회적 역할'에 비유하여 설명해주세요.\n");
		prompt.append(
			"- 십성 분포를 통해 나의 인간관계 패턴을 비유(예: '모두를 챙기는 엄마 같다', '날카로운 비평가 같다')하고, 더 좋은 관계를 위한 조언을 건네주세요.\n\n");

		prompt.append("## 4. 타고난 재능과 적성: 나의 천직은 무엇일까?\n");
		prompt.append("- 십성, 지장간, 신살을 종합하여 나의 핵심 재능을 '나만이 가진 특별한 도구'에 비유해 설명해주세요.\n");
		prompt.append(
			"- 이 도구를 가장 잘 활용할 수 있는 **구체적인 직업 분야 3가지**를 추천하고, 왜 그 일이 '나의 소명'이 될 수 있는지 이유를 이야기해주세요.\n\n");

		prompt.append("## 5. 타고난 재물운: 부자가 될 수 있을까?\n");
		prompt.append("- 나의 재물운을 '물을 담는 그릇'에 비유하여, 그릇의 크기와 형태(안정적인 항아리형, 유동적인 바가지형 등)를 분석해주세요.\n");
		prompt.append("- 돈을 버는 방식을 '사냥'과 '농사'에 비유하여 나에게 맞는 스타일을 조언해주세요.\n");
		prompt.append("- 대운의 흐름에 따라 '수확의 계절'과 '씨앗을 뿌려야 할 시기'를 알려주세요.\n\n");

		prompt.append("## 6. 타고난 애정운: 나의 인연은 언제 어디에?\n");
		prompt.append("- 일지(배우자궁)를 '내 마음의 집'에 비유하여, 어떤 사람이 들어와 살 때 가장 행복할지 이상형을 그려주세요.\n");
		prompt.append(
			"- 나의 연애 스타일을 비유적으로 표현하고(예: '뜨거운 불꽃 같은 사랑', '잔잔한 호수 같은 사랑'), 나의 매력 포인트를 짚어주세요.\n");
		prompt.append("- 대운의 흐름을 분석하여 '사랑의 꽃이 피어나는 시기'를 구체적으로 알려주세요.\n\n");

		prompt.append("## 7. 인생의 흐름: 대운 로드맵과 조언\n");
		prompt.append("- 10년 단위의 대운을 '인생의 각 장(chapter)'에 비유하여 초년, 중년, 말년의 흐름을 조망해주세요.\n");
		prompt.append("- 현재 내가 살고 있는 '인생의 장'의 주제는 무엇이며, 이 시기에 주인공으로서 어떤 미션을 수행해야 하는지 조언해주세요.\n\n");

		prompt.append("## 8. 2025년 신년운세\n");
		prompt.append("- 2025년 을사년(乙巳年)이 나의 사주에 '새로운 손님'처럼 찾아와 어떤 상호작용을 하는지 분석해주세요.\n");
		prompt.append("- 직업, 재물, 연애, 건강 등 분야별로 월별 운세의 흐름을 '날씨 예보'처럼 간략하게 짚어주세요.\n\n");

		prompt.append("## 9. 길신과 신살: 인생의 히든카드와 주의사항\n");
		prompt.append("- 나의 사주에 있는 길신(천을귀인 등)을 '수호천사'나 '인생의 치트키'에 비유하고, 어떻게 활용할 수 있는지 설명해주세요.\n");
		prompt.append(
			"- 주의해야 할 신살(백호대살, 양인살 등)을 '다루기 힘든 명검'에 비유하고, 그 강력한 에너지를 안전하고 긍정적으로 사용하는 방법을 알려주세요.\n\n");

		prompt.append("## 10. 총평: 혜안의 조언\n");
		prompt.append(
			"- 위 모든 분석을 종합하여, 김태우님의 인생 여정에서 가장 중요한 **'인생의 나침반'이 되어줄 핵심 키워드 3가지**를 제시해주세요.\n");
		prompt.append("- 인생의 방향성을 설정하는 데 도움이 될, 마음을 울리는 따뜻하고 힘이 되는 최종 조언으로 아름답게 마무리해주세요.\n");

		return prompt.toString();
	}

	// 사주 기둥 정보 추가 (상세 버전)
	private void appendDetailedPillarInfo(StringBuilder prompt, String pillarName, String meaning,
		PillarElement sky, PillarElement ground) {
		prompt.append(String.format("▶ %s - %s:\n", pillarName, meaning));
		if (sky != null) {
			prompt.append(String.format("  - 천간: %s(%s) | 오행:%s | 십성:%s\n",
				sky.getKorean(), sky.getChinese(), sky.getFiveCircle(), sky.getTenStar()));
		}
		if (ground != null) {
			prompt.append(String.format("  - 지지: %s(%s) | 오행:%s | 십성:%s | **12운성:%s** (%s)\n",
				ground.getKorean(), ground.getChinese(), ground.getFiveCircle(),
				ground.getTenStar(),
				ground.getUnseong(), ground.getUnseongDescription()));
			if (ground.getJijanggan() != null) {
				appendJijangganDetail(prompt, ground.getJijanggan());
			}
		}
	}

	// 지장간 상세 정보 추가
	private void appendJijangganDetail(StringBuilder prompt, JijangganInfo jijanggan) {
		List<JijangganElement> elements = new ArrayList<>();
		if (jijanggan.getFirst() != null) {
			elements.add(jijanggan.getFirst());
		}
		if (jijanggan.getSecond() != null) {
			elements.add(jijanggan.getSecond());
		}
		if (jijanggan.getThird() != null) {
			elements.add(jijanggan.getThird());
		}

		if (!elements.isEmpty()) {
			String jijangganStr = elements.stream()
				.map(j -> String.format("%s(%d%%)", j.getKorean(), j.getRate()))
				.collect(Collectors.joining(", "));
			prompt.append(String.format("  - **지장간**: [ %s ] (숨겨진 성향/재능)\n", jijangganStr));
		}
	}

	// 오행/십성 분포 계산 (지장간 가중치 적용)
	private void calculateDistributionWithJijanggan(ManseryeokCalculationResponse.SajuInfo saju,
		Map<String, Double> ohaengCounts, Map<String, Integer> sipseongCounts) {
		List<PillarElement> pillars = Arrays.asList(
			saju.getYearSky(), saju.getYearGround(), saju.getMonthSky(), saju.getMonthGround(),
			saju.getDaySky(), saju.getDayGround(), saju.getTimeSky(), saju.getTimeGround());

		for (PillarElement p : pillars) {
			if (p != null) {
				// 천간/지지는 10점 가중치
				ohaengCounts.merge(p.getFiveCircle(), 10.0, Double::sum);
				sipseongCounts.merge(p.getTenStar(), 1, Integer::sum);

				// 지장간은 비율(%)을 점수로 환산하여 가중치 적용
				if (p.getJijanggan() != null) {
					addJijangganToCount(p.getJijanggan().getFirst(), ohaengCounts);
					addJijangganToCount(p.getJijanggan().getSecond(), ohaengCounts);
					addJijangganToCount(p.getJijanggan().getThird(), ohaengCounts);
				}
			}
		}
	}

	private void addJijangganToCount(JijangganElement element, Map<String, Double> ohaengCounts) {
		if (element != null && element.getRate() != null) {
			double weight = element.getRate() / 10.0; // 10%를 1점으로 환산
			ohaengCounts.merge(element.getFiveCircle(), weight, Double::sum);
		}
	}

	// 신살 분석 정보 추가
	private void appendSinsalAnalysis(StringBuilder prompt,
		ManseryeokCalculationResponse.SajuInfo saju) {
		List<String> allSinsal = new ArrayList<>();
		if (saju.getSinsalInfo() != null) {
			saju.getSinsalInfo().values().forEach(allSinsal::addAll);
		}
		if (saju.getHasGoegang()) {
			allSinsal.add("괴강살");
		}
		if (saju.getHasBaekho()) {
			allSinsal.add("백호대살");
		}

		if (!allSinsal.isEmpty()) {
			prompt.append(String.format("주요 신살: %s\n",
				allSinsal.stream().distinct().collect(Collectors.joining(", "))));
		}
	}

	// 대운 흐름 생성 및 추가
	private void appendDaewoonFlow(StringBuilder prompt,
		ManseryeokCalculationResponse.SajuInfo saju, String gender) {
		boolean isForward =
			("MALE".equalsIgnoreCase(gender) && "양".equals(saju.getYearSky().getMinusPlus())) ||
				("FEMALE".equalsIgnoreCase(gender) && "음".equals(saju.getYearSky().getMinusPlus()));
		prompt.append(String.format("대운 방향: %s\n", isForward ? "순행(順行)" : "역행(逆行)"));

		String monthPillar = saju.getMonthSky().getChinese() + saju.getMonthGround().getChinese();
		int startIndex = GAPJA_CYCLE.indexOf(monthPillar);

		if (startIndex != -1) {
			prompt.append("【인생 대운 로드맵】\n");
			for (int i = 0; i < 9; i++) { // 90년간의 대운 (9개)
				int age = saju.getBigFortuneNumber() + (i * 10);
				int daewoonIndex =
					isForward ? (startIndex + i + 1) % 60 : (startIndex - i - 1 + 60) % 60;
				String daewoonGanji = GAPJA_CYCLE.get(daewoonIndex);
				prompt.append(String.format("%d세 ~ %d세: %s 대운\n", age, age + 9, daewoonGanji));
			}
		}
	}

	/**
	 * 두 사람의 만세력 데이터를 바탕으로 GPT-5에게 궁합 분석을 요청합니다.
	 */
	public ManseCompatibilityAnalysisResponse analyzeCompatibility(
		String person1Name, ManseryeokCalculationResponse person1Response,
		String person2Name, ManseryeokCalculationResponse person2Response) {

		String person1Ilgan = extractIlgan(person1Response);
		String person2Ilgan = extractIlgan(person2Response);

		log.info("✅ 궁합 분석 요청 시작: {} & {}", person1Name, person2Name);

		try {
			String userPrompt = createCompatibilityPrompt(person1Name, person1Response, person2Name,
				person2Response);
			String input = GPT5_SYSTEM_INSTRUCTION + userPrompt;

			Gpt5Request gpt5Request = new Gpt5Request(
				"gpt-5",
				input,
				16384,
				"high",
				"high"
			);
			String requestBody = objectMapper.writeValueAsString(gpt5Request);

			log.info("GPT-5 궁합 분석 요청 데이터 생성 완료. API 호출 시작...");
			String gptResponse = restClient.post()
				.uri("/responses")
				.body(requestBody)
				.retrieve()
				.body(String.class);

			log.info("GPT-5 궁합 분석 응답 수신 완료.");
			String interpretation = extractContentFromResponseGpt5(gptResponse);

			GptCompatibilityResponse gptData = objectMapper.readValue(interpretation,
				GptCompatibilityResponse.class);
			Integer score = gptData.getScore();
			String analysisText = gptData.getInterpretation();

			CompatibilityResult savedResult = compatibilityResultRepository.save(
				CompatibilityResult.create(null, person1Name, person1Ilgan, person2Name,
					person2Ilgan, score, analysisText)
			);

			return new ManseCompatibilityAnalysisResponse(
				savedResult.getId(), savedResult.getPerson1Name(), savedResult.getPerson1Ilgan(),
				savedResult.getPerson2Name(), savedResult.getPerson2Ilgan(),
				savedResult.getInterpretation(), savedResult.getCompatibilityScore()
			);
		} catch (Exception e) {
			log.error("GPT API 궁합 분석 요청 중 오류 발생: {}", e.getMessage(), e);
			return new ManseCompatibilityAnalysisResponse(null, person1Name, person1Ilgan,
				person2Name, person2Ilgan, "궁합 분석 중 서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.", null);
		}
	}

	/**
	 * 두 사람의 사주 정보를 바탕으로 궁합 분석용 프롬프트를 생성합니다. (데이터 보강 버전)
	 */
	private String createCompatibilityPrompt(String person1Name,
		ManseryeokCalculationResponse person1Saju, String person2Name,
		ManseryeokCalculationResponse person2Saju) {
		StringBuilder prompt = new StringBuilder();

		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 관계 심리학과 사주명리학에 모두 정통한 30년 경력의 궁합 전문 상담가입니다.\n");
		prompt.append(
			"두 사람의 상세 사주 데이터를 기반으로, 단순 길흉 판단을 넘어 서로를 깊이 이해하고 관계를 발전시키는 데 도움이 되는 건설적인 조언을 제공해야 합니다.\n");
		prompt.append("'해요'체를 사용하여 친절하고 따뜻하게 설명해주세요.\n\n");

		prompt.append("### 첫 번째 사람 정보: ").append(person1Name).append(" ###\n");
		appendPersonInfoToPrompt(prompt, person1Name, person1Saju);

		prompt.append("### 두 번째 사람 정보: ").append(person2Name).append(" ###\n");
		appendPersonInfoToPrompt(prompt, person2Name, person2Saju);

		prompt.append("### 종합 궁합 분석 요청 ###\n\n");
		prompt.append("위 두 사람의 데이터를 종합하여 아래 5개 항목을 심층적으로 분석해주세요.\n");
		prompt.append(
			"각 항목은 명확히 구분하고, 사주명리학적 근거(일간 관계, 오행 보완, 합충형파 등)를 제시하되 누구나 쉽게 이해할 수 있도록 설명해주세요.\n\n");
		prompt.append("【분석 시작】\n");
		prompt.append(
			String.format("먼저 \"%s님과 %s님의 궁합을 분석해 드릴게요.\"로 시작하세요.\n\n", person1Name, person2Name));

		prompt.append("## 1. 서로에게 첫눈에 끌리는 부분 (일간 및 외적 에너지)\n");
		prompt.append("- 두 사람의 일간(日干) 오행 관계(상생/상극)가 첫인상과 성격적 끌림에 어떻게 작용하는지 설명해주세요.\n");
		prompt.append("- 각자 사주에 드러난 외적인 분위기(예: 도화살, 12운성)가 서로에게 어떻게 매력으로 작용할지 분석해주세요.\n\n");

		prompt.append("## 2. 함께할 때의 에너지와 안정감 (오행 보완 관계)\n");
		prompt.append(
			"- 각자에게 부족한 오행과 넘치는 오행을 분석하고, 두 사람이 함께 있을 때 서로의 기운을 어떻게 보완해주거나 혹은 부딪히는지 설명해주세요.\n\n");

		prompt.append("## 3. 현실적인 관계에서의 역할과 갈등 요소 (십성 및 합충 관계)\n");
		prompt.append(
			"- 각자의 십성(十星) 분포를 통해 두 사람이 관계에서 어떤 역할을 맡게 될 가능성이 높은지 예측해주세요. (예: 한쪽은 표현하고, 한쪽은 수용하는 등)\n");
		prompt.append(
			"- 두 사람의 지지(地支) 간의 합(合), 충(沖), 형(刑) 관계가 있다면, 어떤 부분에서 갈등이 발생할 수 있고 이를 어떻게 해결할지 조언해주세요.\n\n");

		prompt.append("## 4. 관계 발전을 위한 조언\n");
		prompt.append("- 서로의 장점을 더욱 살리고 단점을 보완해주기 위한 구체적인 행동 지침을 2~3가지 제안해주세요.\n\n");

		prompt.append("## 5. 총평 및 궁합 점수\n");
		prompt.append("- 두 사람의 관계를 한 문장으로 요약하고, 행복한 관계를 위한 핵심 포인트를 강조하며 긍정적으로 마무리해주세요.\n");

		prompt.append("\n\n### 출력 형식 (매우 중요) ###\n");
		prompt.append("위 모든 분석 내용을 종합하여, 반드시 아래와 같은 JSON 형식으로만 응답해야 합니다.\n");
		prompt.append("그 어떤 부가적인 설명이나 markdown 감싸기(` ```json `) 없이 순수한 JSON 객체만 출력해주세요.\n\n");
		prompt.append("{\n");
		prompt.append("  \"score\": <두 사람의 궁합을 0에서 100 사이의 정수 점수로 표현>,\n");
		prompt.append("  \"interpretation\": \"<위 1~5번 항목의 분석 내용을 모두 포함한 상세 해설 문자열>\"\n");
		prompt.append("}\n");

		return prompt.toString();
	}

	private String extractIlgan(ManseryeokCalculationResponse response) {
		if (response != null && response.getSaju() != null
			&& response.getSaju().getDaySky() != null) {
			PillarElement daySky = response.getSaju().getDaySky();
			if (daySky.getKorean() != null && daySky.getFiveCircle() != null) {
				return daySky.getKorean() + daySky.getFiveCircle();
			}
		}
		log.warn("일간(Ilgan) 정보를 추출할 수 없습니다. response: {}", response);
		return "정보 없음";
	}

	private void appendPersonInfoToPrompt(StringBuilder prompt, String name,
		ManseryeokCalculationResponse manseResponse) {
		ManseryeokCalculationResponse.SajuInfo saju = manseResponse.getSaju();

		String sajuPalja = String.format("%s%s %s%s %s%s %s%s",
			saju.getYearSky().getKorean(), saju.getYearGround().getKorean(),
			saju.getMonthSky().getKorean(), saju.getMonthGround().getKorean(),
			saju.getDaySky().getKorean(), saju.getDayGround().getKorean(),
			saju.getTimeSky() != null ? saju.getTimeSky().getKorean() : "?",
			saju.getTimeGround() != null ? saju.getTimeGround().getKorean() : "?"
		);
		prompt.append(String.format("사주명식: %s\n", sajuPalja));
		prompt.append(String.format("일간: %s%s\n", saju.getDaySky().getKorean(),
			saju.getDaySky().getFiveCircle()));

		// 오행 분포
		Map<String, Double> ohaengCounts = new HashMap<>();
		Map<String, Integer> sipseongCounts = new HashMap<>();
		calculateDistributionWithJijanggan(saju, ohaengCounts, sipseongCounts);
		prompt.append("오행 분포(점): ");
		ohaengCounts.forEach((key, value) -> prompt.append(String.format("%s %.1f, ", key, value)));
		prompt.delete(prompt.length() - 2, prompt.length());
		prompt.append("\n");

		// 신살
		List<String> allSinsal = new ArrayList<>();
		if (saju.getSinsalInfo() != null) {
			saju.getSinsalInfo().values().forEach(allSinsal::addAll);
		}
		if (saju.getHasGoegang()) {
			allSinsal.add("괴강살");
		}
		if (saju.getHasBaekho()) {
			allSinsal.add("백호대살");
		}
		if (!allSinsal.isEmpty()) {
			prompt.append(String.format("주요 신살: %s\n",
				allSinsal.stream().distinct().collect(Collectors.joining(", "))));
		}
		prompt.append("\n");
	}

	private String extractContentFromResponseGpt5(String jsonResponse)
		throws JsonProcessingException {
		// ... 기존 코드와 동일 ...
		if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
			throw new IllegalArgumentException("GPT 응답이 비어있습니다.");
		}
		try {
			JsonNode root = objectMapper.readTree(jsonResponse);
			if (root.path("error").isObject()) {
				JsonNode errorNode = root.get("error");
				String errorMessage = errorNode.path("message").asText("알 수 없는 API 오류");
				log.error("GPT API 에러: {}", errorMessage);
				throw new IllegalArgumentException("GPT API 에러: " + errorMessage);
			}
			JsonNode outputNode = root.path("output");
			if (!outputNode.isArray()) {
				log.error("응답에 'output' 배열이 없습니다.");
				throw new IllegalArgumentException("GPT 응답 형식이 올바르지 않습니다. ('output' 배열 누락)");
			}
			for (JsonNode outputItem : outputNode) {
				if ("message".equals(outputItem.path("type").asText())) {
					JsonNode contentArray = outputItem.path("content");
					if (contentArray.isArray() && contentArray.size() > 0) {
						String content = contentArray.get(0).path("text").asText();
						log.info("✅ GPT 응답 성공 - 길이: {} 문자", content.length());
						return content;
					}
				}
			}
			log.error("GPT 응답에서 최종 'text' 필드를 찾을 수 없습니다. JSON 구조 확인 필요.");
			throw new IllegalArgumentException("GPT 응답에서 내용 추출 실패.");
		} catch (JsonProcessingException e) {
			log.error("JSON 파싱 실패: {}", e.getMessage());
			throw e;
		}
	}
}
