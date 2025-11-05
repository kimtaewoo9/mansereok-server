package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.domain.interpret.client.GeminiApiClient;
import com.mansereok.server.domain.interpret.client.GptApiClient;
import com.mansereok.server.domain.interpret.entity.PersonalInfo;
import com.mansereok.server.domain.interpret.repository.PersonalInfoRepository;
import com.mansereok.server.domain.interpret.dto.request.CompatibilityAnalysisRequest;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCreateRequest;
import com.mansereok.server.domain.interpret.dto.response.ChartCreateResponse;
import com.mansereok.server.domain.interpret.dto.response.ChartCreateResponse.BasicChartData.Pillar;
import com.mansereok.server.domain.interpret.dto.response.CompatibilityAnalysisResponse;
import com.mansereok.server.domain.interpret.dto.response.DaeunCreateResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokInterpretationResponse;
import com.mansereok.server.domain.interpret.dto.response.OhaengCreateResponse;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterpretationService {

	private final PersonalInfoRepository personalInfoRepository;

	private final PostellerService postellerService;

	private final GeminiApiClient geminiApiClient;
	private final GptApiClient gptApiClient;

	public ManseryeokInterpretationResponse createInterpretation(
		ManseryeokCreateRequest request,
		Long subcategoryId
	) {
		PersonalInfo savedPersonalInfo = save(request);

		DaeunCreateResponse daeunResponse = postellerService.getDaeun(request);// 대운 API 호출
		ChartCreateResponse chartResponse = postellerService.getChart(request);// 사주 기본 차트 API 호출
		OhaengCreateResponse ohaengResponse = postellerService.getOhaeng(request);// 오행/십성 API 호출

//		log.info("🚀GEMINI 해석 시작");
//		String interpretation = geminiApiClient.interpret(
//			daeunResponse,
//			chartResponse,
//			ohaengResponse,
//			request);

		log.info("🚀 GPT 해석 시작");
		String interpretation = gptApiClient.interpret(
			daeunResponse,
			chartResponse,
			ohaengResponse,
			request);

		// 일간.. 일주의 천간 + 일주의 오행
		// 1. 일주 객체 가져오기
		Pillar dayPillar = chartResponse.getData().getSajuChart().getDayPillar();

		// 2. 일간(천간)의 '이름'과 '오행'의 '이름'을 추출
		// Pillar -> 천간 -> 오행 -> 이름
		String cheonganName = dayPillar.getCheongan().getName(); // "임"
		String ohaengName = dayPillar.getCheongan().getOhaeng().getName(); // "수"

		String ilgan = cheonganName + ohaengName;

		return ManseryeokInterpretationResponse.builder()
			.personalInfo(savedPersonalInfo)
			.ilgan(ilgan)
			.interpretation(interpretation)
			.build();
	}

	private PersonalInfo save(ManseryeokCreateRequest request) {
		PersonalInfo personalInfo = PersonalInfo.from(request);
		return personalInfoRepository.save(personalInfo);
	}

	public CompatibilityAnalysisResponse createCompatibilityAnalysis(
		CompatibilityAnalysisRequest request,
		Long subcategoryId
	) {
		log.info("🚀 궁합 분석 시작: {} & {}", request.getPerson1().getName(),
			request.getPerson2().getName());

		// 두 사람의 개별 만세력 데이터 수집
		PersonalInfo person1Info = save(request.getPerson1());
		PersonalInfo person2Info = save(request.getPerson2());

		// Person 1 데이터
		DaeunCreateResponse person1Daeun = postellerService.getDaeun(request.getPerson1());
		ChartCreateResponse person1Chart = postellerService.getChart(request.getPerson1());
		OhaengCreateResponse person1Ohaeng = postellerService.getOhaeng(request.getPerson1());

		// Person 2 데이터
		DaeunCreateResponse person2Daeun = postellerService.getDaeun(request.getPerson2());
		ChartCreateResponse person2Chart = postellerService.getChart(request.getPerson2());
		OhaengCreateResponse person2Ohaeng = postellerService.getOhaeng(request.getPerson2());

		// 궁합 분석 프롬프트 생성 및 AI 분석 실행
		log.info("🚀 GPT 궁합 해석 시작");
		String compatibilityAnalysis = gptApiClient.analyzeCompatibility(
			person1Daeun, person1Chart, person1Ohaeng, request.getPerson1(),
			person2Daeun, person2Chart, person2Ohaeng, request.getPerson2(),
			request.getCompatibilityType()
		);

		// 기본 궁합 점수 계산 (오행 상생상극 기반)
		CompatibilityAnalysisResponse.CompatibilityScores scores = calculateCompatibilityScores(
			person1Chart, person1Ohaeng, person2Chart, person2Ohaeng
		);

		int overallScore = (scores.getPersonalityCompatibility() +
			scores.getEmotionalHarmony() +
			scores.getCommunicationStyle() +
			scores.getLifeGoals() +
			scores.getEnergyLevel()) / 5;

		return CompatibilityAnalysisResponse.builder()
			.person1Info(person1Info)
			.person2Info(person2Info)
			.compatibilityType(request.getCompatibilityType())
			.overallScore(overallScore)
			.detailedAnalysis(compatibilityAnalysis)
			.scores(scores)
			.build();
	}

	private CompatibilityAnalysisResponse.CompatibilityScores calculateCompatibilityScores(
		ChartCreateResponse person1Chart, OhaengCreateResponse person1Ohaeng,
		ChartCreateResponse person2Chart, OhaengCreateResponse person2Ohaeng) {

		// 일간 오행 비교
		String person1MainElement = person1Chart.getData().getSajuChart().getDayPillar()
			.getCheongan().getOhaeng().getName();
		String person2MainElement = person2Chart.getData().getSajuChart().getDayPillar()
			.getCheongan().getOhaeng().getName();

		// 오행 상생상극 점수 계산
		int personalityScore = calculateElementCompatibility(person1MainElement,
			person2MainElement);

		// 오행 분포 균형 비교
		int emotionalScore = calculateOhaengBalance(person1Ohaeng, person2Ohaeng);

		// 십성 분포 비교
		int communicationScore = calculateSipseongCompatibility(person1Ohaeng, person2Ohaeng);

		return CompatibilityAnalysisResponse.CompatibilityScores.builder()
			.personalityCompatibility(personalityScore)
			.emotionalHarmony(emotionalScore)
			.communicationStyle(communicationScore)
			.lifeGoals(75) // 기본값, AI가 더 정확히 분석
			.energyLevel(70) // 기본값, AI가 더 정확히 분석
			.build();
	}

	private int calculateElementCompatibility(String element1, String element2) {
		// 상생 관계: 목생화, 화생토, 토생금, 금생수, 수생목
		// 상극 관계: 목극토, 토극수, 수극화, 화극금, 금극목

		Map<String, List<String>> compatibility = Map.of(
			"목", List.of("화", "수"), // 목은 화를 생성하고 수에 의해 생성됨
			"화", List.of("토", "목"),
			"토", List.of("금", "화"),
			"금", List.of("수", "토"),
			"수", List.of("목", "금")
		);

		if (element1.equals(element2)) {
			return 80; // 같은 오행은 기본적으로 조화
		} else if (compatibility.get(element1).contains(element2)) {
			return 90; // 상생 관계
		} else {
			return 60; // 상극이거나 중성
		}
	}

	private int calculateOhaengBalance(OhaengCreateResponse person1, OhaengCreateResponse person2) {
		// 두 사람의 오행 분포 비교하여 보완 관계인지 확인
		// 서로 부족한 오행을 보완해주면 높은 점수
		return 75; // 기본 구현
	}

	private int calculateSipseongCompatibility(OhaengCreateResponse person1,
		OhaengCreateResponse person2) {
		// 십성 분포를 통한 성격적 궁합 분석
		return 70; // 기본 구현
	}
}
