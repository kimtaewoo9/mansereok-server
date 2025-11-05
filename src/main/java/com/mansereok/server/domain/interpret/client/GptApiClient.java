package com.mansereok.server.domain.interpret.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.interpret.dto.request.CompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.Gpt5Request;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCreateRequest;
import com.mansereok.server.domain.interpret.dto.response.ChartCreateResponse;
import com.mansereok.server.domain.interpret.dto.response.DaeunCreateResponse;
import com.mansereok.server.domain.interpret.dto.response.OhaengCreateResponse;
import com.mansereok.server.domain.interpret.dto.model.JijangganInfo;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Slf4j
public class GptApiClient {

	private final RestClient restClient;
	private final ObjectMapper objectMapper = new ObjectMapper();

	private static final String GPT5_SYSTEM_INSTRUCTION =
		"--- SYSTEM INSTRUCTION ---\n" +
			"당신은 30년 경력의 전문 사주명리학자입니다. 주어진 사주팔자 정보를 바탕으로 정확하고 건설적인 해석을 제공해주세요. " +
			"부정적인 내용도 포함하되 극복 방안을 함께 제시하고, 운명론적이기보다는 개인의 노력과 선택의 중요성을 강조해주세요. " +
			"'해요'체를 사용하여 부드럽고 친근한 말투를 사용해주세요.\n\n" +
			"--- USER QUERY ---\n";

	public GptApiClient(@Value("${openai.api.key}") String apiKey,
		@Value("${openai.api.base-url:https://api.openai.com}") String baseUrl) {
		this.restClient = RestClient.builder()
			.baseUrl(baseUrl + "/v1")
			.defaultHeader("Authorization", "Bearer " + apiKey)
			.defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.build();
	}

	public String interpret(
		DaeunCreateResponse daeunResponse,
		ChartCreateResponse chartResponse,
		OhaengCreateResponse ohaengResponse,
		ManseryeokCreateRequest request
	) {
		log.info("✅ 사주 해석 요청, 요청한 사람: {}", request.getName());
		try {
			String userPrompt = createPrompt(
				daeunResponse,
				chartResponse,
				ohaengResponse,
				request);

//			GptRequest gptRequest = new GptRequest(
//				"gpt-4o",
//				List.of(
//					new Message("system",
//						"당신은 30년 경력의 전문 사주명리학자입니다. 주어진 사주팔자 정보를 바탕으로 정확하고 건설적인 해석을 제공해주세요. 부정적인 내용도 포함하되 극복 방안을 함께 제시하고, 운명론적이기보다는 개인의 노력과 선택의 중요성을 강조해주세요. 부드럽고 친근한 말투를 사용해주세요."),
//					new Message("user", prompt)
//				),
//				2500,
//				0.7
//			);

			String input = GPT5_SYSTEM_INSTRUCTION + userPrompt;

			Gpt5Request gpt5Request = new Gpt5Request(
				"gpt-5",
				input, // ⚠️ String input으로 전달
				8000,
				"medium",
				"medium"
			);

			String requestBody = objectMapper.writeValueAsString(gpt5Request);

			String gptResponse = restClient.post()
				.uri("/responses") // /v1/responses
				.body(requestBody)
				.retrieve()
				.body(String.class);

			log.info("GPT 응답 결과: {}", gptResponse);
			return extractContentFromResponseGpt5(gptResponse);

		} catch (JsonProcessingException e) {
			log.error("JSON 처리 오류: {}", e.getMessage());
			return "해석 생성 중 데이터 처리 오류가 발생했습니다. 나중에 다시 시도해주세요.";
		} catch (Exception e) {
			log.error("GPT API 요청 중 오류: {}", e.getMessage());
			return "해석 생성 중 API 요청 오류가 발생했습니다. 나중에 다시 시도해주세요.";
		}
	}

	private String createPrompt(
		DaeunCreateResponse daeunResponse,
		ChartCreateResponse chartResponse,
		OhaengCreateResponse ohaengResponse,
		ManseryeokCreateRequest request) {

		// 데이터 추출
		DaeunCreateResponse.SajuData daeunData = Optional.ofNullable(daeunResponse.getData())
			.orElseThrow(() -> new IllegalArgumentException("DaeunCreateResponse data is null"));
		DaeunCreateResponse.DaeunInfo currentDaeun = DaeunCreateResponse.SajuDataUtils.getCurrentDaeun(
			daeunData);
		DaeunCreateResponse.YeonunInfo currentYear = DaeunCreateResponse.SajuDataUtils.getCurrentYearFortune(
			daeunData);
		DaeunCreateResponse.WolunInfo currentMonth = DaeunCreateResponse.SajuDataUtils.getCurrentMonthFortune(
			daeunData);

		ChartCreateResponse.BasicChartData chartData = Optional.ofNullable(chartResponse.getData())
			.orElseThrow(() -> new IllegalArgumentException("ChartCreateResponse data is null"));
		ChartCreateResponse.BasicChartData.SajuChart sajuChart = chartData.getSajuChart();
		ChartCreateResponse.BasicChartData.SinsalInfo sinsalInfo = chartData.getSinsal();
		ChartCreateResponse.BasicChartData.ProfileInfo profile = chartData.getProfile();

		OhaengCreateResponse.AnalysisData ohaengAnalysisData = Optional.ofNullable(
				ohaengResponse.getData())
			.orElseThrow(() -> new IllegalArgumentException("OhaengCreateResponse data is null"));
		List<OhaengCreateResponse.AnalysisData.ElementInfo> ohaengElements = ohaengAnalysisData.getOhaeng();
		List<OhaengCreateResponse.AnalysisData.ElementInfo> sipseongElements = ohaengAnalysisData.getSipseong();

		StringBuilder prompt = new StringBuilder();

		// ===== 시스템 역할 정의 =====
		prompt.append("### 시스템 역할 정의 ###\n");
		prompt.append("당신은 30년 이상의 경력을 가진 최고 수준의 사주명리학 전문가입니다.\n");
		prompt.append("다음 원칙을 반드시 준수하여 분석을 제공하세요:\n");
		prompt.append("1. 정확성: 주어진 데이터를 기반으로 전통 사주명리학 이론에 충실하게 해석\n");
		prompt.append("2. 구체성: 추상적 표현을 피하고 실제 생활에 적용 가능한 구체적 조언 제공\n");
		prompt.append("3. 균형성: 장점과 단점을 균형있게 제시하되, 극복 방안도 함께 제공\n");
		prompt.append("4. 실용성: 학술적 용어는 쉽게 풀어서 설명하고 실생활에 도움되는 조언 포함\n");
		prompt.append("5. 개인화: 이 사람만의 고유한 특징을 찾아 맞춤형 해석 제공\n\n");

		// ===== 분석 대상 정보 =====
		prompt.append("### 분석 대상 기본 정보 ###\n");
		prompt.append(String.format("이름: %s\n", request.getName()));
		prompt.append(String.format("성별: %s\n", request.getGender()));
		prompt.append(String.format("생년월일시(양력): %s\n", profile.getSunBirth()));
		prompt.append(String.format("출생지역: %s\n", profile.getLocation()));
		prompt.append(String.format("사주명식: %s\n\n", profile.getSexagenaryCycle()));

		// ===== 사주 원국 분석 데이터 =====
		prompt.append("### 사주 원국(原局) 데이터 ###\n\n");

		prompt.append("【사주팔자 구성】\n");
		appendDetailedPillarInfo(prompt, "년주(年柱)", sajuChart.getYearPillar(), "조상/어린시절/사회적기반");
		appendDetailedPillarInfo(prompt, "월주(月柱)", sajuChart.getMonthPillar(), "부모/청년기/직업운");

		// 일주 특별 처리 (일간 강조)
		prompt.append("▶ 일주(日柱) - 나자신/배우자/중년기:\n");
		if (sajuChart.getDayPillar() != null && sajuChart.getDayPillar().getCheongan() != null) {
			prompt.append(String.format("  천간(일간): %s%s [핵심정체성]\n",
				sajuChart.getDayPillar().getCheongan().getName(),
				sajuChart.getDayPillar().getCheongan().getChinese()
			));
			prompt.append(String.format("    - 오행: %s, 음양: %s\n",
				sajuChart.getDayPillar().getCheongan().getOhaeng().getName(),
				sajuChart.getDayPillar().getCheongan().getEumyang().getName()
			));
			prompt.append(String.format("  지지: %s%s\n",
				sajuChart.getDayPillar().getJiji().getName(),
				sajuChart.getDayPillar().getJiji().getChinese()
			));
			appendJijangganInfo(prompt, sajuChart.getDayPillar().getJijangganList());
			if (sajuChart.getDayPillar().getUnseong() != null) {
				prompt.append(String.format("  운성: %s(%s)\n",
					sajuChart.getDayPillar().getUnseong().getName(),
					sajuChart.getDayPillar().getUnseong().getChinese()
				));
			}
		}

		appendDetailedPillarInfo(prompt, "시주(時柱)", sajuChart.getTimePillar(), "자녀/노년기/결실");
		prompt.append("\n");

		// ===== 신살 분석 =====
		prompt.append("【신살(神殺) 분석】\n");
		prompt.append("※ 길신은 긍정적 영향, 흉신은 주의가 필요한 부분\n");
		appendSinsalInfo(prompt, "년주", sinsalInfo.getYearSinsal());
		appendSinsalInfo(prompt, "월주", sinsalInfo.getMonthSinsal());
		appendSinsalInfo(prompt, "일진", sinsalInfo.getDaySinsal());
		appendSinsalInfo(prompt, "시진", sinsalInfo.getTimeSinsal());
		prompt.append("\n");

		// ===== 오행 분석 =====
		prompt.append("【오행(五行) 세력 분석】\n");
		prompt.append("※ 균형점은 20%, 과다는 35% 이상, 부족은 10% 이하\n");
		if (ohaengElements != null && !ohaengElements.isEmpty()) {
			// 일간의 오행 찾기
			String ilganOhaeng = sajuChart.getDayPillar() != null &&
				sajuChart.getDayPillar().getCheongan() != null ?
				sajuChart.getDayPillar().getCheongan().getOhaeng().getName() : "";

			for (OhaengCreateResponse.AnalysisData.ElementInfo ohaengInfo : ohaengElements) {
				String marker =
					ohaengInfo.getElement().getName().equals(ilganOhaeng) ? " [일간]" : "";
				prompt.append(String.format("%s%s: %.1f점 (%.1f%%) - %s\n",
					ohaengInfo.getElement().getName(),
					marker,
					ohaengInfo.getPoint(),
					ohaengInfo.getPercent(),
					ohaengInfo.getDescription()
				));
			}

			// 오행 균형 분석
			prompt.append("\n▶ 오행 균형 진단:\n");
			analyzeOhaengBalance(prompt, ohaengElements, ilganOhaeng);
		}
		prompt.append("\n");

		// ===== 십성 분석 =====
		prompt.append("【십성(十星) 분포 분석】\n");
		prompt.append("※ 십성은 사회적 역할과 심리적 특성을 나타냄\n");
		if (sipseongElements != null && !sipseongElements.isEmpty()) {
			for (OhaengCreateResponse.AnalysisData.ElementInfo sipseongInfo : sipseongElements) {
				prompt.append(String.format("%s: %.1f점 (%.1f%%) - %s\n",
					sipseongInfo.getElement().getName(),
					sipseongInfo.getPoint(),
					sipseongInfo.getPercent(),
					sipseongInfo.getDescription()
				));
			}

			// 십성 특징 분석
			prompt.append("\n▶ 십성 특징 진단:\n");
			analyzeSipseongPattern(prompt, sipseongElements);
		}
		prompt.append("\n");

		// ===== 운세 분석 =====
		prompt.append("### 운세 흐름 데이터 ###\n\n");

		// 현재 대운
		prompt.append("【현재 대운(大運) 정보】\n");
		prompt.append(String.format("대운수: %d\n", daeunData.getDaeunNumber()));
		prompt.append(String.format("대운 간지: %s\n", daeunData.getDaeunGanji()));
		if (currentDaeun != null && currentDaeun.getGanji() != null) {
			prompt.append(String.format("현재 대운 (%d세~%d세): %s (%s)\n",
				currentDaeun.getAge(), currentDaeun.getAge() + 9,
				DaeunCreateResponse.SajuDataUtils.getGanjiString(currentDaeun.getGanji()),
				currentDaeun.getGanji().getUnseong() != null ? currentDaeun.getGanji().getUnseong()
					.getName() : ""
			));
			prompt.append("▶ 대운 해석 포인트:\n");
			if (currentDaeun.getGanji().getCheongan() != null) {
				prompt.append(String.format("  - 천간 %s: %s계열로 %s적 특성 강화\n",
					currentDaeun.getGanji().getCheongan().getName(),
					currentDaeun.getGanji().getCheongan().getOhaeng().getName(),
					currentDaeun.getGanji().getCheongan().getSipseong() != null ?
						currentDaeun.getGanji().getCheongan().getSipseong().getName() : ""
				));
			}
			if (currentDaeun.getGanji().getJiji() != null) {
				prompt.append(String.format("  - 지지 %s: %s의 기운으로 %s 운세\n",
					currentDaeun.getGanji().getJiji().getName(),
					currentDaeun.getGanji().getJiji().getOhaeng().getName(),
					currentDaeun.getGanji().getUnseong() != null ?
						currentDaeun.getGanji().getUnseong().getName() : ""
				));
			}
		}
		prompt.append("\n");

		// 연운
		if (currentYear != null && currentYear.getGanji() != null) {
			prompt.append(String.format("【%d년 연운(年運)】\n", currentYear.getYear()));
			prompt.append(String.format("간지: %s\n",
				DaeunCreateResponse.SajuDataUtils.getGanjiString(currentYear.getGanji())));
			if (currentYear.getGanji().getCheongan() != null
				&& currentYear.getGanji().getUnseong() != null) {
				prompt.append(String.format("핵심 키워드: %s + %s\n",
					currentYear.getGanji().getCheongan().getSipseong() != null ?
						currentYear.getGanji().getCheongan().getSipseong().getName() : "",
					currentYear.getGanji().getUnseong().getName()
				));
			}
			prompt.append("\n");
		}

		// 월운
		if (currentMonth != null && currentMonth.getGanji() != null) {
			prompt.append(String.format("【%d월 월운(月運)】\n", currentMonth.getMonth()));
			prompt.append(String.format("간지: %s\n",
				DaeunCreateResponse.SajuDataUtils.getGanjiString(currentMonth.getGanji())));
			if (currentMonth.getGanji().getUnseong() != null) {
				prompt.append(String.format("이달의 기운: %s\n",
					currentMonth.getGanji().getUnseong().getName()
				));
			}
			prompt.append("\n");
		}

		// 전체 대운 흐름
		prompt.append("【인생 대운 로드맵】\n");
		int daeunCount = Math.min(daeunData.getDaeunList().size(), 8); // 최대 8개 대운만
		for (int i = 0; i < daeunCount; i++) {
			DaeunCreateResponse.DaeunInfo daeun = daeunData.getDaeunList().get(i);
			if (daeun != null && daeun.getGanji() != null) {
				boolean isCurrent = (currentDaeun != null
					&& daeun.getAge() == currentDaeun.getAge());
				String marker = isCurrent ? " ◀ 현재" : "";
				prompt.append(String.format("%d세~%d세: %s (%s)%s\n",
					daeun.getAge(), daeun.getAge() + 9,
					DaeunCreateResponse.SajuDataUtils.getGanjiString(daeun.getGanji()),
					daeun.getGanji().getUnseong() != null ? daeun.getGanji().getUnseong().getName()
						: "",
					marker
				));
			}
		}
		prompt.append("\n");

		// ===== 분석 요청사항 =====
		prompt.append("### 종합 분석 요청 ###\n\n");
		prompt.append("위 데이터를 종합하여 아래 11개 항목을 분석해주세요.\n");
		prompt.append("각 항목당 최소 3-5문장 이상 구체적으로 작성하고,\n");
		prompt.append("해요체를 통해 친절하게 설명해주세요.\n");

		prompt.append("【분석 시작】\n");
		prompt.append("먼저 \"[이름]님은 [생년월일] [시각]에 태어나셨습니다.\"로 시작하세요.\n\n");

		prompt.append("## 1. 타고난 성격과 기질 (일간 중심 심층 분석)\n");
		prompt.append("- 일간의 오행/음양 특성이 성격에 미치는 영향\n");
		prompt.append("- 월지와의 관계로 본 성격의 발현 양상\n");
		prompt.append("- 오행 분포가 만드는 성격적 특징\n");
		prompt.append("- 실제 행동 패턴과 사고방식\n\n");

		prompt.append("## 2. 현재 대운이 가져온 변화와 기회\n");
		prompt.append("- 원국과 대운의 상호작용 분석\n");
		prompt.append("- 이 시기에 특별히 발달하는 능력\n");
		prompt.append("- 주목해야 할 기회와 타이밍\n");
		prompt.append("- 대운 활용을 위한 구체적 행동 지침\n\n");

		prompt.append("## 3. 올해와 이달의 운세 흐름\n");
		prompt.append("- 연운이 가져오는 전체적 분위기\n");
		prompt.append("- 월운의 세부적 영향\n");
		prompt.append("- 구체적인 시기별 행동 가이드\n");
		prompt.append("- 피해야 할 시기와 적극적으로 나서야 할 시기\n\n");

		prompt.append("## 4. 인생 전체 흐름과 전환점\n");
		prompt.append("- 대운별 인생 스토리 전개\n");
		prompt.append("- 중요한 전환점과 도약의 시기\n");
		prompt.append("- 용신/기신을 고려한 운의 강약\n");
		prompt.append("- 노년까지의 장기적 전망\n\n");

		prompt.append("## 5. 성공을 위한 맞춤형 전략\n");
		prompt.append("- 부족한 오행을 보완하는 구체적 방법\n");
		prompt.append("- 과다한 오행을 조절하는 실천법\n");
		prompt.append("- 유리한 방위, 색깔, 숫자, 시간대\n");
		prompt.append("- 생활 속 실천 가능한 개운법\n\n");

		prompt.append("## 6. 이상형과 연애 스타일\n");
		prompt.append("- 배우자궁(일지)으로 본 이상형\n");
		prompt.append("- 끌리는 사람의 구체적 특징\n");
		prompt.append("- 연애할 때의 행동 패턴\n");
		prompt.append("- 마음을 여는 조건과 상황\n\n");

		prompt.append("## 7. 연애운과 결혼 시기\n");
		prompt.append("- 구체적인 인연이 들어올 시기 (년도)\n");
		prompt.append("- 어떤 배경의 사람을 만날 가능성\n");
		prompt.append("- 연애/결혼 시 주의사항\n");
		prompt.append("- 행복한 관계를 위한 조언\n\n");

		prompt.append("## 8. 주의해야 할 함정과 위험\n");
		prompt.append("- 신살과 충형파해가 만드는 위험 요소\n");
		prompt.append("- 건강상 취약한 부분\n");
		prompt.append("- 인간관계에서 조심할 유형\n");
		prompt.append("- 재물/사업상 리스크 관리법\n\n");

		prompt.append("## 9. 천직과 적성 (구체적 직업 제시)\n");
		prompt.append(
			"- 사주팔자 원국 외에 지장간에 숨겨진 천간(임성 등)의 역할과 비율을 분석하여 개인의 숨겨진 재능이나 복합적인 성향을 중점적으로 해석해주세요\n");
		prompt.append("- 십성 분포로 본 직업 적성\n");
		prompt.append("- 추천 직업 2개 정도만 구체적으로 제시\n");
		prompt.append("- 피해야 할 직업군과 이유\n");
		prompt.append("- 성공 가능성이 높은 사업 분야\n\n");

		prompt.append("## 10. 인간관계 패턴과 처세술\n");
		prompt.append("- 대인관계에서의 강점과 약점\n");
		prompt.append("- 상사/동료/부하와의 관계 요령\n");
		prompt.append("- 도움이 되는 사람의 특징\n");
		prompt.append("- 인맥 확장 전략\n\n");

		prompt.append("## 11. 핵심 장단점과 개선 포인트\n");
		prompt.append("- 최대 강점 3가지와 활용법\n");
		prompt.append("- 치명적 약점 3가지와 보완법\n");
		prompt.append("- 성격 개선을 위한 실천 과제\n");
		prompt.append("- 인생 성공을 위한 핵심 조언\n\n");

		prompt.append("【주의사항】\n");
		prompt.append("- 각 항목은 명확히 구분하여 작성\n");
		prompt.append("- 전문용어는 괄호 안에 쉬운 설명 추가\n");
		prompt.append("- 부정적 내용도 숨기지 말고 정직하게 전달\n");
		prompt.append("- 모든 해석은 데이터에 근거하여 논리적으로 설명\n");
		prompt.append("- 실생활에 즉시 적용 가능한 조언 포함");

		return prompt.toString();
	}

	// 보조 메서드들
	private void appendDetailedPillarInfo(StringBuilder prompt, String pillarName,
		ChartCreateResponse.BasicChartData.Pillar pillar, String meaning) {
		prompt.append(String.format("▶ %s - %s:\n", pillarName, meaning));
		if (pillar != null && pillar.getCheongan() != null && pillar.getJiji() != null) {
			prompt.append(String.format("  천간: %s%s (오행:%s, 음양:%s",
				pillar.getCheongan().getName(),
				pillar.getCheongan().getChinese(),
				pillar.getCheongan().getOhaeng() != null ? pillar.getCheongan().getOhaeng()
					.getName() : "",
				pillar.getCheongan().getEumyang() != null ? pillar.getCheongan().getEumyang()
					.getName() : ""
			));
			if (pillar.getCheongan().getSipseong() != null) {
				prompt.append(
					String.format(", 십성:%s", pillar.getCheongan().getSipseong().getName()));
			}
			prompt.append(")\n");

			prompt.append(String.format("  지지: %s%s (오행:%s, 음양:%s",
				pillar.getJiji().getName(),
				pillar.getJiji().getChinese(),
				pillar.getJiji().getOhaeng() != null ? pillar.getJiji().getOhaeng().getName() : "",
				pillar.getJiji().getEumyang() != null ? pillar.getJiji().getEumyang().getName() : ""
			));
			if (pillar.getJiji().getSipseong() != null) {
				prompt.append(String.format(", 십성:%s", pillar.getJiji().getSipseong().getName()));
			}
			prompt.append(")\n");

			appendJijangganInfo(prompt, pillar.getJijangganList());
			if (pillar.getUnseong() != null) {
				prompt.append(String.format("  운성: %s(%s)\n",
					pillar.getUnseong().getName(),
					pillar.getUnseong().getChinese()
				));
			}
		}
	}

	private void appendJijangganInfo(StringBuilder prompt, List<JijangganInfo> jijangganList) {
		if (jijangganList != null && !jijangganList.isEmpty()) {
			prompt.append("  지장간: ");
			for (int i = 0; i < jijangganList.size(); i++) {
				JijangganInfo jijanggan = jijangganList.get(i);
				if (i > 0) {
					prompt.append(", ");
				}
				prompt.append(jijanggan.getName());
			}
			prompt.append("\n");
		}
	}

	private void appendSinsalInfo(StringBuilder prompt, String position,
		ChartCreateResponse.BasicChartData.SinsalElement sinsalElement) {
		if (sinsalElement != null && sinsalElement.getName() != null && !sinsalElement.getName()
			.isEmpty()) {
			prompt.append(String.format("- %s: %s(%s)\n",
				position,
				sinsalElement.getName(),
				sinsalElement.getChinese()
			));
		}
	}

	private void analyzeOhaengBalance(StringBuilder prompt,
		List<OhaengCreateResponse.AnalysisData.ElementInfo> ohaengElements, String ilganOhaeng) {
		// 오행 균형 분석 로직
		double maxPercent = 0, minPercent = 100;
		String maxElement = "", minElement = "";

		for (OhaengCreateResponse.AnalysisData.ElementInfo element : ohaengElements) {
			if (element.getPercent() > maxPercent) {
				maxPercent = element.getPercent();
				maxElement = element.getElement().getName();
			}
			if (element.getPercent() < minPercent) {
				minPercent = element.getPercent();
				minElement = element.getElement().getName();
			}
		}

		prompt.append(String.format("  - 가장 강한 오행: %s (%.1f%%) - 과다시 조절 필요\n",
			maxElement, maxPercent));
		prompt.append(String.format("  - 가장 약한 오행: %s (%.1f%%) - 보충 필요\n",
			minElement, minPercent));

		// 일간 강약 판단
		for (OhaengCreateResponse.AnalysisData.ElementInfo element : ohaengElements) {
			if (element.getElement().getName().equals(ilganOhaeng)) {
				if (element.getPercent() > 25) {
					prompt.append("  - 일간 판단: 신강(身强) - 설기 필요\n");
				} else if (element.getPercent() < 15) {
					prompt.append("  - 일간 판단: 신약(身弱) - 부조 필요\n");
				} else {
					prompt.append("  - 일간 판단: 중화(中和) - 균형 상태\n");
				}
				break;
			}
		}
	}

	private void analyzeSipseongPattern(StringBuilder prompt,
		List<OhaengCreateResponse.AnalysisData.ElementInfo> sipseongElements) {
		// 십성 패턴 분석
		StringBuilder pattern = new StringBuilder();
		int officerCount = 0; // 관성 계열
		int wealthCount = 0;  // 재성 계열
		int academicCount = 0; // 인성 계열
		int outputCount = 0;   // 식상 계열
		int peerCount = 0;     // 비겁 계열

		for (OhaengCreateResponse.AnalysisData.ElementInfo element : sipseongElements) {
			String name = element.getElement().getName();
			double percent = element.getPercent();

			if (name.contains("관") || name.contains("살")) {
				officerCount++;
			} else if (name.contains("재")) {
				wealthCount++;
			} else if (name.contains("인")) {
				academicCount++;
			} else if (name.contains("식") || name.contains("상")) {
				outputCount++;
			} else if (name.contains("비") || name.contains("겁")) {
				peerCount++;
			}

			if (percent > 20) {
				if (pattern.length() > 0) {
					pattern.append(", ");
				}
				pattern.append(name).append(" 우세");
			}
		}

		prompt.append("  - 주요 패턴: ").append(pattern.toString()).append("\n");
		prompt.append("  - 성향 분류: ");

		if (officerCount >= 2) {
			prompt.append("권력지향형 ");
		}
		if (wealthCount >= 2) {
			prompt.append("재물추구형 ");
		}
		if (academicCount >= 2) {
			prompt.append("학구형 ");
		}
		if (outputCount >= 2) {
			prompt.append("예술창작형 ");
		}
		if (peerCount >= 2) {
			prompt.append("독립자영형 ");
		}

		prompt.append("\n");
	}

	private String extractContentFromResponseGpt5(String jsonResponse)
		throws JsonProcessingException {
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

			JsonNode outputTextNode = null;

			for (JsonNode outputItem : outputNode) {
				if ("message".equals(outputItem.path("type").asText())) {
					JsonNode contentArray = outputItem.path("content");
					if (contentArray.isArray() && contentArray.size() > 0) {
						outputTextNode = contentArray.get(0).path("text");
						break;
					}
				}
			}

			if (outputTextNode == null || outputTextNode.isMissingNode()
				|| outputTextNode.isNull()) {
				log.error("GPT 응답에서 최종 'text' 필드를 찾을 수 없습니다. JSON 구조 확인 필요.");
				throw new IllegalArgumentException("GPT 응답에서 내용 추출 실패.");
			}

			String content = outputTextNode.asText();

			log.info("✅ GPT 응답 성공 - 길이: {} 문자", content.length());
			return content;

		} catch (JsonProcessingException e) {
			log.error("JSON 파싱 실패: {}", e.getMessage());
			throw e;
		}
	}

	public String analyzeCompatibility(
		DaeunCreateResponse person1Daeun, ChartCreateResponse person1Chart,
		OhaengCreateResponse person1Ohaeng, ManseryeokCreateRequest person1Request,
		DaeunCreateResponse person2Daeun, ChartCreateResponse person2Chart,
		OhaengCreateResponse person2Ohaeng, ManseryeokCreateRequest person2Request,
		CompatibilityAnalysisRequest.CompatibilityType compatibilityType
	) {
		log.info("✅ 궁합 분석 요청: {} & {}", person1Request.getName(), person2Request.getName());
		try {
			String userPrompt = createCompatibilityPrompt(
				person1Daeun, person1Chart, person1Ohaeng, person1Request,
				person2Daeun, person2Chart, person2Ohaeng, person2Request,
				compatibilityType
			);

			String input = GPT5_SYSTEM_INSTRUCTION + userPrompt;

			Gpt5Request gpt5Request = new Gpt5Request(
				"gpt-5",
				input,
				10000,
				"medium",
				"medium"
			);

			String requestBody = objectMapper.writeValueAsString(gpt5Request);

			String gptResponse = restClient.post()
				.uri("/responses")
				.body(requestBody)
				.retrieve()
				.body(String.class);

			return extractContentFromResponseGpt5(gptResponse);

		} catch (JsonProcessingException e) {
			log.error("JSON 처리 오류: {}", e.getMessage());
			return "궁합 분석 생성 중 데이터 처리 오류가 발생했습니다. 나중에 다시 시도해주세요.";
		} catch (Exception e) {
			log.error("GPT API 요청 중 오류: {}", e.getMessage());
			return "궁합 분석 생성 중 API 요청 오류가 발생했습니다. 나중에 다시 시도해주세요.";
		}
	}

	private String createCompatibilityPrompt(
		DaeunCreateResponse person1Daeun, ChartCreateResponse person1Chart,
		OhaengCreateResponse person1Ohaeng, ManseryeokCreateRequest person1Request,
		DaeunCreateResponse person2Daeun, ChartCreateResponse person2Chart,
		OhaengCreateResponse person2Ohaeng, ManseryeokCreateRequest person2Request,
		CompatibilityAnalysisRequest.CompatibilityType compatibilityType) {

		StringBuilder prompt = new StringBuilder();

		prompt.append("다음은 두 사람의 사주팔자 궁합 분석 요청입니다.\n\n");
		prompt.append("궁합 분석 유형: ").append(compatibilityType.getDescription()).append("\n\n");

		// 첫 번째 사람 정보
		prompt.append("【첫 번째 사람】\n");
		appendPersonInfo(prompt, person1Chart, person1Ohaeng, person1Daeun, person1Request);

		prompt.append("\n【두 번째 사람】\n");
		appendPersonInfo(prompt, person2Chart, person2Ohaeng, person2Daeun, person2Request);

		// 궁합 분석 요청사항
		prompt.append("\n위 두 사람의 사주팔자 정보를 바탕으로 다음 항목들에 대해 상세히 분석해주세요:\n\n");

		prompt.append("## 1. 기본 성격 궁합 분석\n");
		prompt.append("- 일간(일주 천간) 오행 상생상극 관계\n");
		prompt.append("- 각자의 성격적 특성과 조화 정도\n");
		prompt.append("- 서로를 이해하고 받아들일 수 있는 부분\n\n");

		prompt.append("## 2. 오행 균형 및 보완 관계\n");
		prompt.append("- 각자의 오행 분포와 부족한 오행\n");
		prompt.append("- 서로의 오행이 보완 관계에 있는지 분석\n");
		prompt.append("- 함께 있을 때의 에너지 균형\n\n");

		prompt.append("## 3. 십성을 통한 역할 및 관계 역학\n");
		prompt.append("- 각자의 십성 분포를 통한 성향 분석\n");
		prompt.append("- 관계에서의 역할 분담과 주도권\n");
		prompt.append("- 갈등 요소와 해결 방안\n\n");

		prompt.append("## 4. 현재 운세의 궁합\n");
		prompt.append("- 현재 대운과 연운의 조화\n");
		prompt.append("- 각자의 현재 대운 시기와 특성\n");
		prompt.append("- 두 사람의 대운 흐름이 조화로운지 분석\n");
		prompt.append("- 지금 시기에 만나는 것의 의미\n");
		prompt.append("- 앞으로의 운세 흐름과 관계 전망\n\n");

		prompt.append("## 5. 실용적 관계 조언\n");
		prompt.append("- 서로의 장점을 살리고 단점을 보완하는 방법\n");
		prompt.append("- 갈등이 생겼을 때의 대처법\n");
		prompt.append("- 관계 발전을 위한 구체적인 실천 방안\n");
		prompt.append("- 주의해야 할 시기와 상황\n\n");

		prompt.append("각 항목을 명확히 구분하여 작성하고, 사주명리학적 근거를 제시하되 ");
		prompt.append("일반인이 이해하기 쉽게 설명해주세요. 단순한 점수나 길흉보다는 ");
		prompt.append("서로를 이해하고 좋은 관계를 만들어가는데 도움이 되는 조언을 중심으로 해주세요.");

		return prompt.toString();
	}

	private void appendPersonInfo(StringBuilder prompt, ChartCreateResponse chartResponse,
		OhaengCreateResponse ohaengResponse, DaeunCreateResponse daeunResponse,
		ManseryeokCreateRequest request) {

		ChartCreateResponse.BasicChartData chartData = chartResponse.getData();
		ChartCreateResponse.BasicChartData.SajuChart sajuChart = chartData.getSajuChart();
		ChartCreateResponse.BasicChartData.ProfileInfo profile = chartData.getProfile();

		prompt.append(String.format("이름: %s, 성별: %s\n", request.getName(), request.getGender()));
		prompt.append(
			String.format("생년월일: %s, 출생지: %s\n", profile.getSunBirth(), profile.getLocation()));
		prompt.append(String.format("사주명식: %s\n", profile.getSexagenaryCycle()));

		// 사주팔자
		prompt.append("사주팔자:\n");
		prompt.append(String.format("- 년주: %s%s\n",
			sajuChart.getYearPillar().getCheongan().getName(),
			sajuChart.getYearPillar().getJiji().getName()));
		prompt.append(String.format("- 월주: %s%s\n",
			sajuChart.getMonthPillar().getCheongan().getName(),
			sajuChart.getMonthPillar().getJiji().getName()));
		prompt.append(String.format("- 일주: %s%s (일간: %s, 오행: %s)\n",
			sajuChart.getDayPillar().getCheongan().getName(),
			sajuChart.getDayPillar().getJiji().getName(),
			sajuChart.getDayPillar().getCheongan().getName(),
			sajuChart.getDayPillar().getCheongan().getOhaeng().getName()));
		prompt.append(String.format("- 시주: %s%s\n",
			sajuChart.getTimePillar().getCheongan().getName(),
			sajuChart.getTimePillar().getJiji().getName()));

		// 오행 분포
		prompt.append("오행 분포:\n");
		OhaengCreateResponse.AnalysisData analysisData = ohaengResponse.getData();
		if (analysisData.getOhaeng() != null) {
			for (OhaengCreateResponse.AnalysisData.ElementInfo ohaeng : analysisData.getOhaeng()) {
				prompt.append(String.format("- %s: %.1f점 (%.1f%%)\n",
					ohaeng.getElement().getName(), ohaeng.getPoint(), ohaeng.getPercent()));
			}
		}

		// 십성 분포
		prompt.append("십성 분포:\n");
		if (analysisData.getSipseong() != null) {
			for (OhaengCreateResponse.AnalysisData.ElementInfo sipseong : analysisData.getSipseong()) {
				prompt.append(String.format("- %s: %.1f점 (%.1f%%)\n",
					sipseong.getElement().getName(), sipseong.getPoint(), sipseong.getPercent()));
			}
		}

		// 대운 정보 추가 (createPrompt 메서드 방식 참고)
		if (daeunResponse != null && daeunResponse.getData() != null) {
			DaeunCreateResponse.SajuData daeunData = daeunResponse.getData();
			DaeunCreateResponse.DaeunInfo currentDaeun = DaeunCreateResponse.SajuDataUtils.getCurrentDaeun(
				daeunData);

			prompt.append("현재 대운:\n");
			if (currentDaeun != null && currentDaeun.getGanji() != null) {
				prompt.append(String.format("- %d세~%d세: %s",
					currentDaeun.getAge(),
					currentDaeun.getAge() + 9,
					DaeunCreateResponse.SajuDataUtils.getGanjiString(currentDaeun.getGanji())
				));

				if (currentDaeun.getGanji().getUnseong() != null) {
					prompt.append(
						String.format(" (%s)", currentDaeun.getGanji().getUnseong().getName()));
				}
				prompt.append("\n");

				// 천간/지지 상세 정보
				if (currentDaeun.getGanji().getCheongan() != null) {
					prompt.append(String.format("  천간: %s (오행:%s",
						currentDaeun.getGanji().getCheongan().getName(),
						currentDaeun.getGanji().getCheongan().getOhaeng() != null ?
							currentDaeun.getGanji().getCheongan().getOhaeng().getName() : ""
					));
					if (currentDaeun.getGanji().getCheongan().getSipseong() != null) {
						prompt.append(String.format(", 십성:%s",
							currentDaeun.getGanji().getCheongan().getSipseong().getName()));
					}
					prompt.append(")\n");
				}

				if (currentDaeun.getGanji().getJiji() != null) {
					prompt.append(String.format("  지지: %s (오행:%s)\n",
						currentDaeun.getGanji().getJiji().getName(),
						currentDaeun.getGanji().getJiji().getOhaeng() != null ?
							currentDaeun.getGanji().getJiji().getOhaeng().getName() : ""
					));
				}
			}
		}

		prompt.append("\n");
	}
}
