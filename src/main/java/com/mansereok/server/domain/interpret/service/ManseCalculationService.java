package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.domain.interpret.calculator.RelationCalculator;
import com.mansereok.server.domain.interpret.calculator.SinsalCalculator;
import com.mansereok.server.domain.interpret.calculator.UnseongCalculator;
import com.mansereok.server.domain.interpret.calculator.YongsinCalculator;
import com.mansereok.server.domain.interpret.calculator.YongsinCalculator.YongsinResult;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCalculationRequest;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.SajuInfo;
import com.mansereok.server.domain.interpret.entity.Manse;
import com.mansereok.server.domain.interpret.repository.ManseRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManseCalculationService {

	private final ManseRepository manseRepository;
	private final SajuDataService sajuDataService;
	private final UnseongCalculator unseongCalculator;
	private final SinsalCalculator sinsalCalculator;
	private final RelationCalculator relationCalculator;
	private final YongsinCalculator yongsinCalculator;

	public ManseryeokCalculationResponse calculate(ManseryeokCalculationRequest request) {
		try {
			log.info("만세력 계산 시작: solarDate={}, gender={}, isLunar={}",
				request.getSolarDate(), request.getGender(), request.getIsLunar());

			// 1. 생년월일을 삼주(양력)로 변환
			SamjuResult samju = convertBirthToSamju(
				request.getIsLunar() ? "LUNAR" : "SOLAR",
				request.getSolarDate(),
				request.getSolarTime()
			);

			// 2. 생년월일시(양력) 생성
			LocalDateTime solarDatetime = LocalDateTime.of(
				samju.getSolarDate(),
				request.getSolarTime()
			);

			// 3. 순행(true), 역행(false) 판단
			boolean direction = isRightDirection(request.getGender(), samju.getYearSky());

			// 4. 절입시간 가져오기
			LocalDateTime seasonTime = getSeasonStartTime(direction, solarDatetime);

			// 5. 대운수 및 대운 시작년 가져오기
			BigFortuneResult bigFortune = getBigFortuneNumber(direction, seasonTime, solarDatetime);

			// 6. 시주 가져오기
			TimePillarResult timePillar = getTimePillar(samju.getDaySky(), request.getSolarTime());

			// 7. 일간 한자
			String ilganChinese = samju.getDaySky();

			// 8. 신살 계산
			Map<String, List<String>> sinsalInfo = sinsalCalculator.analyzeAllSinsal(
				ilganChinese,
				samju.getYearGround(),
				samju.getMonthGround(),
				samju.getDayGround(),
				timePillar.getTimeGround()
			);

			// 9. 괴강살 체크
			boolean hasGoegang = sinsalCalculator.hasGoegang(ilganChinese, samju.getDayGround());

			// 10. 백호대살 체크
			boolean hasBaekho = sinsalCalculator.hasBaekho(ilganChinese, samju.getDayGround());

			// 11. 공망 계산
			List<String> gongmang = sinsalCalculator.calculateGongmang(ilganChinese,
				samju.getDayGround());

			// [추가] 12. 지지 간 관계 분석 (합, 충, 원진 등)
			// 일지 vs 월지 (사회적 환경과의 조화)
			List<String> dayMonthRel = relationCalculator.analyzeRelation(samju.getDayGround(),
				samju.getMonthGround());

			// 일지 vs 년지 (배경과의 조화)
			List<String> dayYearRel = relationCalculator.analyzeRelation(samju.getDayGround(),
				samju.getYearGround());

			// [추가] 13. 사주 전체에서 삼합(국)이 형성되었는지 체크
			List<String> fullSamhap = relationCalculator.findFullSamhap(
				java.util.stream.Stream.of(
						samju.getYearGround(),
						samju.getMonthGround(),
						samju.getDayGround(),
						timePillar.getTimeGround()
					)
					.filter(Objects::nonNull) // null 값(시주가 없는 경우) 자동 제거
					.collect(Collectors.toList())
			);

			List<String> skyRelations = new ArrayList<>();
			// 일간 vs 월간 (사회적 정신적 조화)
			List<String> dayMonthSky = relationCalculator.analyzeSkyRelation(samju.getDaySky(),
				samju.getMonthSky());
			if (!dayMonthSky.isEmpty()) {
				skyRelations.add("일간-월간: " + String.join(",", dayMonthSky));
			}

			// 일간 vs 시간 (말년/자식/생각)
			if (timePillar.getTimeSky() != null) {
				List<String> dayTimeSky = relationCalculator.analyzeSkyRelation(samju.getDaySky(),
					timePillar.getTimeSky());
				if (!dayTimeSky.isEmpty()) {
					skyRelations.add("일간-시간: " + String.join(",", dayTimeSky));
				}
			}
			String skyRelationStr = String.join(" / ", skyRelations);

			SajuInfo sajuInfo = SajuInfo.builder()
				.bigFortuneNumber(bigFortune.getBigFortuneNumber())
				.bigFortuneStartYear(bigFortune.getBigFortuneStart())
				.seasonStartTime(samju.getSeasonStartTime())
				.yearSky(formatChinese(samju.getYearSky(), samju.getDaySky(), false, ilganChinese))
				.yearGround(
					formatChineseWithUnseong(samju.getYearGround(), ilganChinese, samju.getDaySky(),
						true, ilganChinese))
				.monthSky(
					formatChinese(samju.getMonthSky(), samju.getDaySky(), false, ilganChinese))
				.monthGround(formatChineseWithUnseong(samju.getMonthGround(), ilganChinese,
					samju.getDaySky(), true, ilganChinese))
				.daySky(formatChinese(samju.getDaySky(), samju.getDaySky(), false, ilganChinese))
				.dayGround(
					formatChineseWithUnseong(samju.getDayGround(), ilganChinese, samju.getDaySky(),
						true, ilganChinese))
				.timeSky(timePillar.getTimeSky() != null ? formatChinese(timePillar.getTimeSky(),
					samju.getDaySky(), false, ilganChinese) : null)
				.timeGround(timePillar.getTimeGround() != null ? formatChineseWithUnseong(
					timePillar.getTimeGround(), ilganChinese, samju.getDaySky(), true, ilganChinese)
					: null)
				.sinsalInfo(sinsalInfo)
				.hasGoegang(hasGoegang)
				.hasBaekho(hasBaekho)
				.gongmang(gongmang)
				.dayMonthRelation(dayMonthRel)
				.dayYearRelation(dayYearRel)
				.samhap(fullSamhap)
				.skyRelation(skyRelationStr) // [추가]
				.build();

			// 용신 계산
			YongsinResult yongsinResult = yongsinCalculator.analyzeYongsin(sajuInfo);
			sajuInfo.setYongsinInfo(yongsinResult);

			// 응답 생성
			return ManseryeokCalculationResponse.builder()
				.input(ManseryeokCalculationResponse.InputInfo.builder()
					.solarDate(request.getSolarDate())
					.solarTime(request.getSolarTime())
					.gender(request.getGender())
					.isLunar(request.getIsLunar())
					.build())
				.saju(sajuInfo)
				.build();
		} catch (Exception e) {
			log.error("만세력 계산 중 오류 발생", e);
			throw new RuntimeException("만세력 계산 중 오류가 발생했습니다: " + e.getMessage());
		}
	}

	/**
	 * 운성을 포함한 지지 정보 포맷팅
	 */
	private ManseryeokCalculationResponse.PillarElement formatChineseWithUnseong(
		String chinese, String ilganChinese, String daySky, boolean isGround,
		String ilganChineseForJijanggan) {

		ManseryeokCalculationResponse.PillarElement.PillarElementBuilder builder =
			formatChineseToBuilder(chinese, daySky, isGround, ilganChineseForJijanggan);

		// 운성 계산 및 추가
		if (isGround) {
			String unseong = unseongCalculator.calculate(ilganChinese, chinese);
			if (unseong != null) {
				builder.unseong(unseong);
				builder.unseongDescription(unseongCalculator.getUnseongDescription(unseong));
			} else {
				// ⭐ 운성 계산 실패 경고 로깅 강화
				log.warn("⚠️ 운성 계산 실패: 일간={}, 지지={}", ilganChinese, chinese);
			}
		}

		return builder.build();
	}

	/**
	 * 기존 formatChinese를 Builder 패턴으로 분리 ⭐ ilganChinese 파라미터 추가 (지장간 십성 계산용)
	 */
	private ManseryeokCalculationResponse.PillarElement.PillarElementBuilder formatChineseToBuilder(
		String chinese, String daySky, boolean isGround, String ilganChinese) {

		Map<String, String> koreanData = sajuDataService.convertChineseToKorean();
		Map<String, Map<String, String>> tenStarData = sajuDataService.getTenStar();
		Map<String, String> minusPlusData = sajuDataService.getMinusPlus();

		Map<String, String> tenStar = tenStarData.get(daySky);
		if (tenStar == null) {
			throw new RuntimeException("일간 " + daySky + "의 십성 데이터를 찾을 수 없습니다");
		}

		String tenStarInfo = tenStar.get(chinese);
		if (tenStarInfo == null) {
			throw new RuntimeException("간지 " + chinese + "의 십성 정보를 찾을 수 없습니다");
		}

		String[] tenStarParts = tenStarInfo.split(",");
		if (tenStarParts.length != 2) {
			throw new RuntimeException("십성 정보 형식이 올바르지 않습니다: " + tenStarInfo);
		}

		ManseryeokCalculationResponse.PillarElement.PillarElementBuilder builder =
			ManseryeokCalculationResponse.PillarElement.builder()
				.chinese(chinese)
				.korean(koreanData.get(chinese))
				.fiveCircle(tenStarParts[1])
				.fiveCircleColor(getColor(tenStarParts[1]))
				.tenStar(tenStarParts[0])
				.minusPlus(minusPlusData.get(chinese));

		if (isGround) {
			builder.jijanggan(getJijangganInfo(chinese, ilganChinese));  // ⭐ ilganChinese 전달
		}

		return builder;
	}

	private ManseryeokCalculationResponse.PillarElement formatChinese(
		String chinese, String daySky, boolean isGround, String ilganChinese) {
		return formatChineseToBuilder(chinese, daySky, isGround, ilganChinese).build();
	}

	/**
	 * 생년월일을 삼주로 변환
	 */
	private SamjuResult convertBirthToSamju(String birthdayType, LocalDate birthday,
		LocalTime time) {
		LocalTime birthtime = time != null ? time : LocalTime.of(12, 0);

		// 23:30 ~ 23:59 자시에 태어난 경우 다음날로 처리
		if (time != null &&
			((time.isAfter(LocalTime.of(23, 30)) || time.equals(LocalTime.of(23, 30))) &&
				time.isBefore(LocalTime.of(23, 59, 59)))) {
			birthday = birthday.plusDays(1);
			log.info("자시 처리: 날짜를 다음날로 변경 -> {}", birthday);
		}

		log.info("만세력 데이터 조회: birthdayType={}, birthday={}", birthdayType, birthday);

		Manse samju = birthdayType.equals("SOLAR") ?
			manseRepository.findBySolarDate(birthday)
				.orElseThrow(() -> new RuntimeException("해당 양력 날짜의 만세력 데이터를 찾을 수 없습니다.")) :
			manseRepository.findByLunarDate(birthday)
				.orElseThrow(() -> new RuntimeException("해당 음력 날짜의 만세력 데이터를 찾을 수 없습니다."));

		// 절입일인 경우 처리
		if (samju.getSeason() != null && !samju.getSeason().isEmpty()) {
			log.info("절입일 처리: season={}, seasonStartTime={}", samju.getSeason(),
				samju.getSeasonStartTime());

			LocalDateTime seasonTime = samju.getSeasonStartTime();
			LocalDateTime solarDatetime = LocalDateTime.of(birthday, birthtime);

			if (solarDatetime.isBefore(seasonTime)) {
				log.info("절입시간 이전 출생: 이전 날짜 만세력 사용");
				Manse previousManse = manseRepository.findBySolarDate(birthday.minusDays(1))
					.orElseThrow(() -> new RuntimeException("이전 날짜의 만세력 데이터를 찾을 수 없습니다"));

				return SamjuResult.builder()
					.solarDate(samju.getSolarDate())
					.yearSky(previousManse.getYearSky())
					.yearGround(previousManse.getYearGround())
					.monthSky(previousManse.getMonthSky())
					.monthGround(previousManse.getMonthGround())
					.daySky(previousManse.getDaySky())
					.dayGround(previousManse.getDayGround())
					.seasonStartTime(samju.getSeasonStartTime() != null ?
						samju.getSeasonStartTime().toString() : null)
					.build();
			}
		}

		return SamjuResult.builder()
			.solarDate(samju.getSolarDate())
			.yearSky(samju.getYearSky())
			.yearGround(samju.getYearGround())
			.monthSky(samju.getMonthSky())
			.monthGround(samju.getMonthGround())
			.daySky(samju.getDaySky())
			.dayGround(samju.getDayGround())
			.seasonStartTime(samju.getSeasonStartTime() != null ?
				samju.getSeasonStartTime().toString() : null)
			.build();
	}

	/**
	 * 순행(true), 역행(false) 판단 (성별, 연간)
	 */
	private boolean isRightDirection(String gender, String yearSky) {
		String minusPlus = sajuDataService.getMinusPlus().get(yearSky);

		if (minusPlus == null) {
			throw new RuntimeException("연간 " + yearSky + "의 음양 정보를 찾을 수 없습니다");
		}

		boolean result;
		if (("MALE".equals(gender) && "양".equals(minusPlus)) ||
			("FEMALE".equals(gender) && "음".equals(minusPlus))) {
			result = true; // 순행
		} else {
			result = false; // 역행
		}

		log.info("대운 방향 판단: gender={}, yearSky={}, minusPlus={}, direction={}",
			gender, yearSky, minusPlus, result ? "순행" : "역행");

		return result;
	}

	/**
	 * 절입 시간 가져오기
	 */
	private LocalDateTime getSeasonStartTime(boolean direction, LocalDateTime solarDatetime) {
		Manse manse;

		if (direction) {
			manse = manseRepository.findFirstBySeasonStartTimeGreaterThanEqualOrderBySolarDateAsc(
					solarDatetime)
				.orElseThrow(() -> new RuntimeException("순행 절입 시간을 찾을 수 없습니다"));
		} else {
			manse = manseRepository.findFirstBySeasonStartTimeLessThanEqualOrderBySolarDateDesc(
					solarDatetime)
				.orElseThrow(() -> new RuntimeException("역행 절입 시간을 찾을 수 없습니다"));
		}

		log.info("절입시간 조회 완료: seasonStartTime={}, direction={}",
			manse.getSeasonStartTime(), direction ? "순행" : "역행");

		return manse.getSeasonStartTime();
	}

	/**
	 * 대운수 및 대운 시작 구하기
	 */
	private BigFortuneResult getBigFortuneNumber(boolean direction, LocalDateTime seasonStartTime,
		LocalDateTime solarDatetime) {
		long diffDays;

		if (direction) {
			diffDays = ChronoUnit.DAYS.between(solarDatetime, seasonStartTime);
		} else {
			diffDays = ChronoUnit.DAYS.between(seasonStartTime, solarDatetime);
		}

		int divider = (int) (diffDays / 3);
		int remainder = (int) (diffDays % 3);

		int bigFortuneNumber = divider;
		if (diffDays < 4) {
			bigFortuneNumber = 1;
		}

		if (remainder == 2) {
			bigFortuneNumber += 1;
		}

		int bigFortuneStart = solarDatetime.getYear() + bigFortuneNumber;

		log.info("대운 계산 완료: diffDays={}, bigFortuneNumber={}, bigFortuneStart={}",
			diffDays, bigFortuneNumber, bigFortuneStart);

		return BigFortuneResult.builder()
			.bigFortuneNumber(bigFortuneNumber)
			.bigFortuneStart(bigFortuneStart)
			.build();
	}

	/**
	 * 시주 계산하기
	 */
	private TimePillarResult getTimePillar(String daySky, LocalTime time) {
		if (time == null) {
			log.info("출생시간이 없어 시주 계산 생략");
			return TimePillarResult.builder()
				.timeSky(null)
				.timeGround(null)
				.build();
		}

		String timeKey = getTimeJuIndex(time);
		if (timeKey == null) {
			return TimePillarResult.builder()
				.timeSky(null)
				.timeGround(null)
				.build();
		}

		Map<String, Map<String, String[]>> timeJuData2 = sajuDataService.getTimeJuData2();
		Map<String, String[]> dayData = timeJuData2.get(daySky);

		if (dayData == null) {
			throw new RuntimeException("일간 " + daySky + "의 시주 데이터를 찾을 수 없습니다");
		}

		if (dayData.containsKey(timeKey)) {
			String[] timeJu = dayData.get(timeKey);
			log.info("시주 계산 완료: daySky={}, time={}, timeKey={}, timeSky={}, timeGround={}",
				daySky, time, timeKey, timeJu[0], timeJu[1]);

			return TimePillarResult.builder()
				.timeSky(timeJu[0])
				.timeGround(timeJu[1])
				.build();
		}

		throw new RuntimeException("시주 계산 실패: daySky=" + daySky + ", timeKey=" + timeKey);
	}

	private String getTimeJuIndex(LocalTime time) {
		Map<String, LocalTime[]> timeJuData = sajuDataService.getTimeJuData();

		for (Map.Entry<String, LocalTime[]> entry : timeJuData.entrySet()) {
			LocalTime[] timeRange = entry.getValue();
			if (time.compareTo(timeRange[0]) >= 0 && time.compareTo(timeRange[1]) <= 0) {
				return entry.getKey();
			}
		}

		// 자시 특별 처리 (23:30-01:29)
		if ((time.isAfter(LocalTime.of(23, 30)) || time.equals(LocalTime.of(23, 30))) ||
			(time.isBefore(LocalTime.of(1, 30)) && time.isAfter(LocalTime.of(0, 0)))) {
			return "0";
		}

		return null;
	}

	private String getColor(String value) {
		return switch (value) {
			case "목" -> "#4CAF50";
			case "화" -> "#F44336";
			case "토" -> "#FFD600";
			case "금" -> "#E0E0E0";
			case "수" -> "#039BE5";
			default -> "";
		};
	}

	/**
	 * ⭐ 지장간 정보 가져오기 (십성 계산 포함)
	 *
	 * @param jiji         지지 한자
	 * @param ilganChinese 일간 한자 (십성 계산용)
	 */
	private ManseryeokCalculationResponse.JijangganInfo getJijangganInfo(String jiji,
		String ilganChinese) {
		Map<String, Map<String, Object>> jijangganData = sajuDataService.getJijangan();
		Map<String, Object> jijiData = jijangganData.get(jiji);

		if (jijiData == null) {
			return null;
		}

		return ManseryeokCalculationResponse.JijangganInfo.builder()
			.first(
				createJijangganElement((Map<String, Object>) jijiData.get("first"), ilganChinese))
			.second(
				createJijangganElement((Map<String, Object>) jijiData.get("second"), ilganChinese))
			.third(
				createJijangganElement((Map<String, Object>) jijiData.get("third"), ilganChinese))
			.build();
	}

	/**
	 * ⭐ 지장간 요소 생성 (십성 계산 추가)
	 *
	 * @param elementData  지장간 요소 데이터
	 * @param ilganChinese 일간 한자 (십성 계산용)
	 */
	@SuppressWarnings("unchecked")
	private ManseryeokCalculationResponse.JijangganElement createJijangganElement(
		Map<String, Object> elementData, String ilganChinese) {
		if (elementData == null) {
			return null;
		}

		String chinese = (String) elementData.get("chinese");

		// ⭐ 지장간의 십성 계산 (SajuDataService.getTenStar() 활용)
		String tenStar = null;
		if (chinese != null && ilganChinese != null) {
			Map<String, Map<String, String>> tenStarData = sajuDataService.getTenStar();
			Map<String, String> ilganTenStarMap = tenStarData.get(ilganChinese);
			if (ilganTenStarMap != null) {
				String tenStarInfo = ilganTenStarMap.get(chinese);
				if (tenStarInfo != null) {
					String[] parts = tenStarInfo.split(",");
					tenStar = parts.length > 0 ? parts[0] : null; // "편인,수" → "편인"
					log.debug("지장간 십성 계산: 일간={}, 지장간천간={}, 십성={}", ilganChinese, chinese, tenStar);
				}
			}
		}

		return ManseryeokCalculationResponse.JijangganElement.builder()
			.chinese(chinese)
			.korean((String) elementData.get("korean"))
			.fiveCircle((String) elementData.get("fiveCircle"))
			.fiveCircleColor((String) elementData.get("fiveCircleColor"))
			.minusPlus((String) elementData.get("minusPlus"))
			.rate((Integer) elementData.get("rate"))
			.tenStar(tenStar)  // ⭐ 십성 추가
			.build();
	}

	// Inner classes for return types
	@lombok.Data
	@lombok.Builder
	private static class SamjuResult {

		private LocalDate solarDate;
		private String yearSky;
		private String yearGround;
		private String monthSky;
		private String monthGround;
		private String daySky;
		private String dayGround;
		private String seasonStartTime;
	}

	@lombok.Data
	@lombok.Builder
	private static class BigFortuneResult {

		private Integer bigFortuneNumber;
		private Integer bigFortuneStart;
	}

	@lombok.Data
	@lombok.Builder
	private static class TimePillarResult {

		private String timeSky;
		private String timeGround;
	}
}
