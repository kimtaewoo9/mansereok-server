package com.mansereok.server.domain.interpret.dto.response;

import com.mansereok.server.domain.interpret.entity.PersonalInfo;
import com.mansereok.server.domain.interpret.dto.request.CompatibilityAnalysisRequest.CompatibilityType;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CompatibilityAnalysisResponse {

	// 여기서 생년월일시, 양력, 음력 정보 전달.
	private PersonalInfo person1Info;
	private PersonalInfo person2Info;

	private CompatibilityType compatibilityType;

	// 전체 궁합 점수 (0-100)
	private int overallScore;

	// AI가 생성한 상세 궁합 분석
	private String detailedAnalysis;

	// 각 영역별 점수
	private CompatibilityScores scores;

	@Data
	@Builder
	public static class CompatibilityScores {

		private int personalityCompatibility; // 성격 궁합
		private int emotionalHarmony;        // 감정적 조화
		private int communicationStyle;      // 소통 방식
		private int lifeGoals;              // 인생 목표
		private int energyLevel;            // 에너지 레벨
	}
}
