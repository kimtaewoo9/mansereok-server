package com.mansereok.server.domain.interpret.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCreateRequest;
import com.mansereok.server.domain.interpret.dto.response.ChartCreateResponse;
import com.mansereok.server.domain.interpret.dto.response.DaeunCreateResponse;
import com.mansereok.server.domain.interpret.dto.response.OhaengCreateResponse;
import com.mansereok.server.domain.interpret.dto.model.JijangganInfo;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Slf4j
public class GeminiApiClient {

	private final RestClient restClient;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final String apiKey;
	private static final String GEMINI_MODEL_PATH = "gemini-1.5-flash-8b"; // 모델 이름만 정의

	public GeminiApiClient(@Value("${gemini.api.key}") String apiKey,
		@Value("${gemini.api.base-url:https://generativelanguage.googleapis.com/v1beta/models}") String baseUrl) {
		this.apiKey = apiKey;
		// baseUrl에 모델 경로까지 포함하여 RestClient를 초기화합니다.
		// 이렇게 하면 .uri() 호출 시 상대 경로만 전달해도 완전한 URL이 됩니다.
		this.restClient = RestClient.builder()
			.baseUrl(baseUrl + "/" + GEMINI_MODEL_PATH) // <-- 여기를 수정!
			.defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
			.build();
	}

	public String interpret(
		DaeunCreateResponse daeunResponse, // 대운/연운/월운
		ChartCreateResponse chartResponse, // 일간/사주팔자
		OhaengCreateResponse ohaengResponse, // 오행/십성
		ManseryeokCreateRequest request
	) {
		log.info("Gemini 사주 해석 요청: name={}", request.getName());
		try {
			String prompt = createPrompt(
				daeunResponse,
				chartResponse,
				ohaengResponse,
				request);

			Map<String, Object> geminiRequest = Map.of(
				"contents", List.of(Map.of(
					"parts", List.of(Map.of(
						"text", prompt
					))
				)),
				"generationConfig", Map.of(
					"temperature", 0.3,
					"maxOutputTokens", 3000,
					"topP", 0.9,
					"topK", 20
				)
			);

			String requestBody = objectMapper.writeValueAsString(geminiRequest);

			String geminiResponse = restClient.post()
				.uri(":generateContent?key=" + apiKey) // <-- 여기도 수정! 상대 경로로 변경
				.body(requestBody)
				.retrieve()
				.body(String.class);

			return extractContentFromResponse(geminiResponse);

		} catch (JsonProcessingException e) {
			log.error("JSON 처리 오류: {}", e.getMessage());
			return "해석 생성 중 데이터 처리 오류가 발생했습니다. 나중에 다시 시도해주세요.";
		} catch (Exception e) {
			log.error("Gemini API 요청 중 오류: {}", e.getMessage(), e);
			return "해석 생성 중 API 요청 오류가 발생했습니다. 나중에 다시 시도해주세요.";
		}
	}

	// ... (createPrompt, appendPillarInfo 등 나머지 코드는 이전과 동일)
	private String createPrompt(
		DaeunCreateResponse daeunResponse,
		ChartCreateResponse chartResponse,
		OhaengCreateResponse ohaengResponse,
		ManseryeokCreateRequest request) {

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

		prompt.append("당신은 30년 경력의 전문 사주명리학자입니다. 주어진 사주팔자 정보를 바탕으로 정확하고 건설적인 해석을 제공해주세요. ");
		prompt.append("부정적인 내용도 포함하되 극복 방안을 함께 제시하고, 운명론적이기보다는 개인의 노력과 선택의 중요성을 강조해주세요.\n\n");

		prompt.append("다음은 사주팔자 정보입니다.\n\n");
		prompt.append(String.format("이름: %s, 성별: %s\n", request.getName(), request.getGender()));
		prompt.append(String.format("생년월일(양력): %s, 출생지역: %s\n", profile.getSunBirth(),
			profile.getLocation()));
		prompt.append(String.format("사주명식: %s\n\n", profile.getSexagenaryCycle()));

		prompt.append("【사주팔자】\n");
		appendPillarInfo(prompt, "년주", sajuChart.getYearPillar());
		appendPillarInfo(prompt, "월주", sajuChart.getMonthPillar());

		// 일주 (일간 강조)
		prompt.append(String.format("일주: %s%s (일간: %s %s, 오행: %s, 음양: %s)\n",
			sajuChart.getDayPillar().getCheongan().getName(),
			sajuChart.getDayPillar().getJiji().getName(),
			sajuChart.getDayPillar().getCheongan().getName(),
			sajuChart.getDayPillar().getCheongan().getChinese(),
			sajuChart.getDayPillar().getCheongan().getOhaeng().getName(),
			sajuChart.getDayPillar().getCheongan().getEumyang().getName()
		));
		appendJijangganInfo(prompt, sajuChart.getDayPillar().getJijangganList());
		if (sajuChart.getDayPillar().getUnseong() != null) {
			prompt.append(String.format("  - 운성: %s (%s)\n",
				sajuChart.getDayPillar().getUnseong().getName(),
				sajuChart.getDayPillar().getUnseong().getChinese()
			));
		} else {
			prompt.append("  - 운성: 정보 없음\n");
		}

		appendPillarInfo(prompt, "시주", sajuChart.getTimePillar());
		prompt.append("\n");

		prompt.append("【신살 분석】\n");
		appendSinsalInfo(prompt, "년주", sinsalInfo.getYearSinsal());
		appendSinsalInfo(prompt, "월주", sinsalInfo.getMonthSinsal());
		appendSinsalInfo(prompt, "일진", sinsalInfo.getDaySinsal());
		appendSinsalInfo(prompt, "시진", sinsalInfo.getTimeSinsal());
		prompt.append("\n");

		prompt.append("【오행 분석】\n");
		if (ohaengElements != null && !ohaengElements.isEmpty()) {
			for (OhaengCreateResponse.AnalysisData.ElementInfo ohaengInfo : ohaengElements) {
				prompt.append(String.format("- %s: %.1f점 (%.1f%%) - %s\n",
					ohaengInfo.getElement().getName(),
					ohaengInfo.getPoint(),
					ohaengInfo.getPercent(),
					ohaengInfo.getDescription()
				));
			}
			prompt.append("\n");
		} else {
			prompt.append("- 오행 분석 정보가 없습니다.\n");
		}

		prompt.append("【십성 분석】\n");
		if (sipseongElements != null && !sipseongElements.isEmpty()) {
			for (OhaengCreateResponse.AnalysisData.ElementInfo sipseongInfo : sipseongElements) {
				prompt.append(String.format("- %s: %.1f점 (%.1f%%) - %s\n",
					sipseongInfo.getElement().getName(),
					sipseongInfo.getPoint(),
					sipseongInfo.getPercent(),
					sipseongInfo.getDescription()
				));
			}
			prompt.append("\n");
		} else {
			prompt.append("- 십성 분석 정보가 없습니다.\n");
		}

		prompt.append("【현재 대운 정보】\n");
		prompt.append("- 대운수: ").append(daeunData.getDaeunNumber()).append("\n");
		prompt.append("- 대운 간지: ").append(daeunData.getDaeunGanji()).append("\n");
		if (currentDaeun != null && currentDaeun.getGanji() != null) {
			prompt.append("- 현재 대운 (").append(currentDaeun.getAge()).append("세): ")
				.append(DaeunCreateResponse.SajuDataUtils.getGanjiString(currentDaeun.getGanji()))
				.append(" (").append(currentDaeun.getGanji().getUnseong().getName()).append(")\n");
			prompt.append("  - 천간: ").append(currentDaeun.getGanji().getCheongan().getName())
				.append(" (").append(currentDaeun.getGanji().getCheongan().getChinese())
				.append("), 오행: ")
				.append(currentDaeun.getGanji().getCheongan().getOhaeng().getName())
				.append(", 십성: ")
				.append(currentDaeun.getGanji().getCheongan().getSipseong().getName())
				.append(", 음양: ")
				.append(currentDaeun.getGanji().getCheongan().getEumyang().getName()).append("\n");
			prompt.append("  - 지지: ").append(currentDaeun.getGanji().getJiji().getName())
				.append(" (").append(currentDaeun.getGanji().getJiji().getChinese())
				.append("), 오행: ").append(currentDaeun.getGanji().getJiji().getOhaeng().getName())
				.append(", 십성: ").append(currentDaeun.getGanji().getJiji().getSipseong().getName())
				.append(", 음양: ").append(currentDaeun.getGanji().getJiji().getEumyang().getName())
				.append("\n");
			appendJijangganInfo(prompt, currentDaeun.getGanji().getJijangganList());
		}
		prompt.append("\n");

		if (currentYear != null && currentYear.getGanji() != null) {
			prompt.append("【현재 연운 (").append(currentYear.getYear()).append("년)】\n");
			prompt.append("- 간지: ").append(
					DaeunCreateResponse.SajuDataUtils.getGanjiString(currentYear.getGanji()))
				.append("\n");
			prompt.append("- 천간 십성: ")
				.append(currentYear.getGanji().getCheongan().getSipseong().getName()).append("\n");
			prompt.append("- 지지 운성: ").append(currentYear.getGanji().getUnseong().getName())
				.append("\n");
			appendJijangganInfo(prompt, currentYear.getGanji().getJijangganList());
			prompt.append("\n");
		}

		if (currentMonth != null && currentMonth.getGanji() != null) {
			prompt.append("【현재 월운 (").append(currentMonth.getMonth()).append("월)】\n");
			prompt.append("- 간지: ").append(
					DaeunCreateResponse.SajuDataUtils.getGanjiString(currentMonth.getGanji()))
				.append("\n");
			prompt.append("- 천간 십성: ")
				.append(currentMonth.getGanji().getCheongan().getSipseong().getName()).append("\n");
			prompt.append("- 지지 운성: ").append(currentMonth.getGanji().getUnseong().getName())
				.append("\n");
			appendJijangganInfo(prompt, currentMonth.getGanji().getJijangganList());
			prompt.append("\n");
		}

		prompt.append("【주요 대운 흐름 (총 ").append(daeunData.getDaeunList().size()).append("개 대운)】\n");
		daeunData.getDaeunList().forEach(daeun -> {
			if (daeun != null && daeun.getGanji() != null) {
				prompt.append("- ").append(daeun.getAge()).append("세 (").append(daeun.getYear())
					.append("년): ")
					.append(DaeunCreateResponse.SajuDataUtils.getGanjiString(daeun.getGanji()))
					.append(" (").append(daeun.getGanji().getUnseong().getName()).append(")\n");
			}
		});
		prompt.append("\n");

		prompt.append("위 정보를 바탕으로 다음 5개 항목에 대해 상세히 해석해주세요:\n\n");
		prompt.append("## 1. 성격 및 기본 성향 (일간, 사주팔자 오행 및 십성 특징 포함)\n");
		prompt.append("## 2. 현재 대운의 특징과 기회 (현재 대운 간지, 십성, 운성 및 주요 신살 영향)\n");
		prompt.append("## 3. 현재 시기 운세 (올해/이번달 연운/월운의 간지, 십성, 운성 영향)\n");
		prompt.append("## 4. 인생 전반적 흐름과 전망 (전체 대운 흐름, 사주 원국의 강약, 용신/희신 추론)\n");
		prompt.append("## 5. 실용적 조언 및 개선 방안 (오행의 균형, 십성의 활용, 신살의 길흉 조절 방안)\n\n");
		prompt.append(
			"각 항목을 명확히 구분하여 작성하고, 사주명리학적 용어를 적절히 사용하되 일반인이 이해하기 쉽게 설명해주세요. 구체적이고 실용적인 조언을 포함해주세요.");

		return prompt.toString();
	}

	// 각 기둥 정보 (천간, 지지, 오행, 음양, 지장간, 운성)를 추가하는 헬퍼 메서드
	private void appendPillarInfo(StringBuilder prompt, String pillarName,
		ChartCreateResponse.BasicChartData.Pillar pillar) {
		if (pillar != null && pillar.getCheongan() != null && pillar.getJiji() != null) {
			prompt.append(String.format("%s: %s%s (천간: %s %s, 지지: %s %s)\n",
				pillarName,
				pillar.getCheongan().getName(),
				pillar.getJiji().getName(),
				pillar.getCheongan().getName(),
				pillar.getCheongan().getOhaeng().getName(),
				pillar.getJiji().getName(),
				pillar.getJiji().getOhaeng().getName()
			));

			// Pillar 클래스에서 지장간 리스트를 직접 가져옵니다.
			appendJijangganInfo(prompt, pillar.getJijangganList());

			// Pillar 클래스에서 운성 정보를 직접 가져옵니다.
			if (pillar.getUnseong() != null) {
				prompt.append(String.format("  - 운성: %s (%s)\n",
					pillar.getUnseong().getName(),
					pillar.getUnseong().getChinese()
				));
			} else {
				prompt.append("  - 운성: 정보 없음\n");
			}
		} else {
			prompt.append(String.format("%s: 정보 없음\n", pillarName));
		}
	}

	// 지장간 정보를 추가하는 헬퍼 메서드 (공통 JijangganInfo 클래스 사용)
	private void appendJijangganInfo(StringBuilder prompt, List<JijangganInfo> jijangganList) {
		if (jijangganList != null && !jijangganList.isEmpty()) {
			prompt.append("  - 지장간: ");
			for (int i = 0; i < jijangganList.size(); i++) {
				prompt.append(jijangganList.get(i).getName());
				if (i < jijangganList.size() - 1) {
					prompt.append(", ");
				}
			}
			prompt.append("\n");
		}
	}

	// 신살 정보를 추가하는 헬퍼 메서드
	private void appendSinsalInfo(StringBuilder prompt, String pillarName,
		ChartCreateResponse.BasicChartData.SinsalElement sinsalElement) {
		if (sinsalElement != null && sinsalElement.getName() != null && !sinsalElement.getName()
			.isEmpty()) {
			prompt.append(String.format("- %s 신살: %s (%s)\n",
				pillarName,
				sinsalElement.getName(),
				sinsalElement.getChinese()
			));
		}
	}


	private String extractContentFromResponse(String jsonResponse) throws JsonProcessingException {
		JsonNode root = objectMapper.readTree(jsonResponse);
		JsonNode candidatesNode = root.path("candidates");
		if (candidatesNode.isArray() && candidatesNode.size() > 0) {
			JsonNode contentNode = candidatesNode.get(0).path("content").path("parts");
			if (contentNode.isArray() && contentNode.size() > 0) {
				JsonNode textNode = contentNode.get(0).path("text");
				if (textNode != null && !textNode.isNull()) {
					return textNode.asText();
				}
			}
		}
		log.error("Gemini 응답에서 'text' 필드를 찾을 수 없습니다. 응답: {}", jsonResponse);
		throw new IllegalArgumentException("Gemini 응답 형식이 올바르지 않습니다.");
	}
}
