package com.mansereok.server.domain.interpret.postprocess;

import java.util.List;

/**
 * 무료 운세 상품(101~106)마다 다른 마지막 손질.
 *
 * <p>여섯 상품은 앞단(라벨 제거, 기간 표기 통일, 줄글 합치기)이 완전히 같고 마지막 문단 정리만 다르다.
 * 그 차이만 상수 하나씩으로 세워 두고, 공통 앞단은 {@link FreeFortuneNormalizationRule} 이 맡는다.
 *
 * <p>대부분은 소제목 앞에서 끊는 규칙이고, 길이 기준(최소/최대 글자 수)만 상품 성격에 따라 다르다.
 * 키워드(102), 케미(104), 3월 월운(106)만 전용 손질이 따로 있다.
 */
enum FreeFortuneStyle {

	/** 2026 변화 운세. 다섯 생활 영역을 소제목으로 쓴다. */
	CHANGES_2026(101L) {
		@Override
		String applyToAnalysis(String text) {
			return ParagraphSplitter.ensureContextAwareParagraphBreaks(text,
				List.of("환경의 변화", "인간관계의 변화", "연애와 애정운", "학업 및 성취운", "건강 및 컨디션"),
				150, 250);
		}
	},

	/** 2026 키워드 운세. 상담 멘트를 지우고 제목 아래 본문만 문단으로 나눈다. */
	KEYWORD(102L) {
		@Override
		String applyToAnalysis(String text) {
			return KeywordParagraphs.ensureParagraphBreaks(
				KeywordParagraphs.removeMetaPhrases(text));
		}

		@Override
		String applyToSummary(String text) {
			return KeywordParagraphs.removeMetaPhrases(text);
		}
	},

	/** 플러팅 운세. 문단이 셋으로 정해져 있어 기준 길이가 짧다. */
	FLIRTING(103L) {
		@Override
		String applyToAnalysis(String text) {
			return ParagraphSplitter.ensureContextAwareParagraphBreaks(text,
				List.of("당신의 매력 포인트", "나만의 플러팅 비법", "이것만은 주의하세요"),
				140, 230);
		}
	},

	/** 케미 궁합. 대괄호 추천 라벨을 지우고 추천 항목 단위로 문단을 나눈다. */
	CHEMISTRY(104L) {
		@Override
		String applyToAnalysis(String text) {
			String withoutLabels = text.replaceAll(
				"\\[(아이돌\\s*추천|배우\\s*추천|캐릭터\\s*추천)\\]\\s*", "");
			return ChemistryParagraphs.ensureParagraphBreaks(withoutLabels);
		}
	},

	/** 오늘의 운세. 하루치라 문단이 가장 짧다. */
	TODAY(105L) {
		@Override
		String applyToAnalysis(String text) {
			return ParagraphSplitter.ensureContextAwareParagraphBreaks(text,
				List.of("오늘의 총운", "재물운", "금전운", "애정운", "성취운"),
				130, 220);
		}
	},

	/** 3월 월운. 화면이 섹션 단위로 고정돼 있어 섹션 재조립이 필요하다. */
	MARCH_MONTHLY(106L) {
		@Override
		String applyToAnalysis(String text) {
			return MarchMonthlySections.normalize(text);
		}
	};

	private final Long subcategoryId;

	FreeFortuneStyle(Long subcategoryId) {
		this.subcategoryId = subcategoryId;
	}

	Long subcategoryId() {
		return subcategoryId;
	}

	/** 공통 앞단을 거친 본문에 상품별 문단 정리를 적용한다. */
	abstract String applyToAnalysis(String text);

	/** 요약에 상품별 손질을 적용한다. 대부분은 손댈 것이 없다. */
	String applyToSummary(String text) {
		return text;
	}
}
