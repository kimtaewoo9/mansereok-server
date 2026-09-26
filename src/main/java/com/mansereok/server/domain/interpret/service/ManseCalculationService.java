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
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ManseCalculationService {

	// 월운의 "지금" 은 서버 시간대와 상관없이 한국 시각으로 센다.
	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
	// 월운은 지금이 든 절기부터 12개월이다. 마지막 달이 끝나는 시각까지 알려면 절입이 하나 더 필요해 달 수 + 1 개를 한 번에 읽는다.
	private static final int MONTHLY_FORTUNE_MONTHS = 12;
	private static final Limit MONTHLY_SEASON_LIMIT = Limit.of(MONTHLY_FORTUNE_MONTHS + 1);

	private final ManseRepository manseRepository;
	private final SajuDataService sajuDataService;
	private final UnseongCalculator unseongCalculator;
	private final SinsalCalculator sinsalCalculator;
	private final RelationCalculator relationCalculator;
	private final YongsinCalculator yongsinCalculator;
	// 월운을 세기 시작할 "지금". 테스트는 Clock.fixed 로 절입 경계 시각을 고정한다.
	private final Clock clock;

	public ManseryeokCalculationResponse calculate(ManseryeokCalculationRequest request) {
		try {
			// 생년월일·출생시간·성별은 개인정보라 DEBUG 로만 남긴다. 운영 로그 수준(INFO)에서는 나오지 않는다.
			log.debug("만세력 계산 시작: solarDate={}, gender={}, isLunar={}, leapMonth={}",
				request.getSolarDate(), request.getGender(), request.getIsLunar(),
				request.getLeapMonth());

			LocalTime rawSolarTime = request.getSolarTime();
			boolean timeUnknown = rawSolarTime == null;
			List<String> uncertaintyNotes = new ArrayList<>();
			if (timeUnknown) {
				uncertaintyNotes.add("출생시간 미입력: 시주는 계산하지 않았습니다.");
				uncertaintyNotes.add("출생시간 미입력: 야자시(23:30 이후) 보정은 적용하지 않았습니다.");
			}

			SamjuResult samju = convertBirthToSamju(
				request.getIsLunar() ? "LUNAR" : "SOLAR",
				request.getSolarDate(),
				rawSolarTime,
				request.getLeapMonth()
			);
			if (samju.isSeasonBoundaryUncertain()) {
				uncertaintyNotes.add("절입일 출생 + 시간 미입력으로 연주/월주 경계가 불확정입니다.");
			}

			boolean direction = isRightDirection(request.getGender(), samju.getYearSky());
			BigFortuneRangeResult bigFortune = calculateBigFortuneRange(direction, samju, rawSolarTime,
				timeUnknown, uncertaintyNotes);
			TimePillarResult timePillar = getTimePillar(samju.getDaySky(), rawSolarTime);
			String ilganChinese = samju.getDaySky();

			Map<String, List<String>> sinsalInfo = sinsalCalculator.analyzeAllSinsal(
				ilganChinese,
				samju.getYearSky(),
				samju.getYearGround(),
				samju.getMonthSky(),
				samju.getMonthGround(),
				samju.getDaySky(),
				samju.getDayGround(),
				timePillar.getTimeSky(),
				timePillar.getTimeGround()
			);
			boolean hasGoegang = sinsalCalculator.hasGoegang(ilganChinese, samju.getDayGround());
			boolean hasBaekho = sinsalCalculator.hasBaekho(ilganChinese, samju.getDayGround());
			List<String> gongmang = sinsalCalculator.calculateGongmang(ilganChinese,
				samju.getDayGround());

			// [100점짜리 수정] 12. 지지 관계 분석 (모든 조합 6개)
			List<String> allGroundRelations = new ArrayList<>();
			addRelations(allGroundRelations, "년지-월지",
				relationCalculator.analyzeRelation(samju.getYearGround(), samju.getMonthGround()));
			addRelations(allGroundRelations, "년지-일지",
				relationCalculator.analyzeRelation(samju.getYearGround(), samju.getDayGround()));
			addRelations(allGroundRelations, "월지-일지",
				relationCalculator.analyzeRelation(samju.getMonthGround(), samju.getDayGround()));

			if (timePillar.getTimeGround() != null) {
				addRelations(allGroundRelations, "년지-시지",
					relationCalculator.analyzeRelation(samju.getYearGround(),
						timePillar.getTimeGround()));
				addRelations(allGroundRelations, "월지-시지",
					relationCalculator.analyzeRelation(samju.getMonthGround(),
						timePillar.getTimeGround()));
				addRelations(allGroundRelations, "일지-시지",
					relationCalculator.analyzeRelation(samju.getDayGround(),
						timePillar.getTimeGround()));
			}

			// 13. 천간 관계 분석 (모든 조합)
			List<String> allSkyRelations = new ArrayList<>();
			addRelations(allSkyRelations, "년간-월간",
				relationCalculator.analyzeSkyRelation(samju.getYearSky(), samju.getMonthSky()));
			addRelations(allSkyRelations, "년간-일간",
				relationCalculator.analyzeSkyRelation(samju.getYearSky(), samju.getDaySky()));
			addRelations(allSkyRelations, "월간-일간",
				relationCalculator.analyzeSkyRelation(samju.getMonthSky(), samju.getDaySky()));

			if (timePillar.getTimeSky() != null) {
				addRelations(allSkyRelations, "년간-시간",
					relationCalculator.analyzeSkyRelation(samju.getYearSky(),
						timePillar.getTimeSky()));
				addRelations(allSkyRelations, "월간-시간",
					relationCalculator.analyzeSkyRelation(samju.getMonthSky(),
						timePillar.getTimeSky()));
				addRelations(allSkyRelations, "일간-시간",
					relationCalculator.analyzeSkyRelation(samju.getDaySky(),
						timePillar.getTimeSky()));
			}

			// 14. 삼합 체크
			List<String> fullSamhap = relationCalculator.findFullSamhap(
				java.util.stream.Stream.of(samju.getYearGround(), samju.getMonthGround(),
						samju.getDayGround(), timePillar.getTimeGround())
					.filter(Objects::nonNull).collect(Collectors.toList())
			);

			// 15. DTO 빌드
			SajuInfo sajuInfo = SajuInfo.builder()
				.bigFortuneNumber(bigFortune.getBigFortuneNumber())
				.bigFortuneNumberMin(bigFortune.getBigFortuneNumberMin())
				.bigFortuneNumberMax(bigFortune.getBigFortuneNumberMax())
				.bigFortuneStartYear(bigFortune.getBigFortuneStart())
				.bigFortuneStartYearMin(bigFortune.getBigFortuneStartMin())
				.bigFortuneStartYearMax(bigFortune.getBigFortuneStartMax())
				.seasonStartTime(samju.getSeasonStartTime())
				.uncertaintyNotes(uncertaintyNotes.isEmpty() ? null : uncertaintyNotes)
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
				// [수정] 통합된 리스트 주입
				.groundRelations(allGroundRelations)
				.skyRelations(allSkyRelations)
				.samhap(fullSamhap)
				.monthlyFortunes(calculateMonthlyFortunes(samju.getDaySky(), ilganChinese))
				.build();

			// 16. 용신 계산
			YongsinResult yongsinResult = yongsinCalculator.analyzeYongsin(sajuInfo);
			sajuInfo.setYongsinInfo(yongsinResult);

					return ManseryeokCalculationResponse.builder()
						.input(ManseryeokCalculationResponse.InputInfo.builder()
							.solarDate(request.getSolarDate())
							.solarTime(rawSolarTime)
							.timeUnknown(timeUnknown)
							.gender(normalizeGender(request.getGender()))
							.isLunar(request.getIsLunar())
							.build())
					.saju(sajuInfo)
					.build();

			} catch (IllegalArgumentException e) {
				log.warn("만세력 계산 입력값 오류: {}", e.getMessage());
				throw e;
			} catch (Exception e) {
				log.error("만세력 계산 중 오류 발생", e);
				throw new RuntimeException("만세력 계산 중 오류가 발생했습니다: " + e.getMessage());
			}
		}

	// 헬퍼 메서드: 관계 리스트에 추가
	private void addRelations(List<String> targetList, String label, List<String> relations) {
		if (relations != null && !relations.isEmpty()) {
			targetList.add(label + ": " + String.join(", ", relations));
		}
	}

	private List<ManseryeokCalculationResponse.MonthlyFortune> calculateMonthlyFortunes(
		String daySky, String ilganChinese) {
		List<ManseryeokCalculationResponse.MonthlyFortune> monthlyFortunes = new ArrayList<>();
		LocalDateTime nowKst = LocalDateTime.now(clock.withZone(SEOUL));

		Manse currentBoundary = manseRepository
			.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(nowKst)
			.orElse(null);
		if (currentBoundary == null || currentBoundary.getSeasonStartTime() == null) {
			log.warn("월운 계산 실패: 현재 절입 기준점 조회 불가");
			return null;
		}

		// 지금이 든 절입(첫 행)부터 절입 시각 순서로 달 수 + 1 개까지 한 번에 읽는다. 이웃한 두 절입이 한 달의 시작과 끝이다.
		// 표 끝(2100년)에 가까워 그만큼이 안 되면 있는 만큼만 세고, 다음 절입이 없는 마지막 달은 끝을 비운다.
		List<Manse> boundaries = manseRepository
			.findBySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(
				currentBoundary.getSeasonStartTime(), MONTHLY_SEASON_LIMIT);
		int months = Math.min(MONTHLY_FORTUNE_MONTHS, boundaries.size());
		for (int i = 0; i < months; i++) {
			Manse startBoundary = boundaries.get(i);
			LocalDateTime periodStart = startBoundary.getSeasonStartTime();
			LocalDateTime periodEnd = i + 1 < boundaries.size()
				? boundaries.get(i + 1).getSeasonStartTime().minusSeconds(1)
				: null;

			ManseryeokCalculationResponse.PillarElement monthSky = formatChinese(
				startBoundary.getMonthSky(), daySky, false, ilganChinese
			);
			ManseryeokCalculationResponse.PillarElement monthGround = formatChineseWithUnseong(
				startBoundary.getMonthGround(), ilganChinese, daySky, true, ilganChinese
			);

			monthlyFortunes.add(ManseryeokCalculationResponse.MonthlyFortune.builder()
				.year(periodStart.getYear())
				.month(periodStart.getMonthValue())
				.season(startBoundary.getSeason())
				.periodStart(periodStart)
				.periodEnd(periodEnd)
				.monthSky(monthSky)
				.monthGround(monthGround)
				.build());
		}

		return monthlyFortunes.isEmpty() ? null : monthlyFortunes;
	}

	// ... (나머지 private 메서드들은 기존 코드 그대로 사용) ...
	// formatChinese, convertBirthToSamju 등등 복사 붙여넣기 하세요.
	private ManseryeokCalculationResponse.PillarElement formatChineseWithUnseong(
		String chinese, String ilganChinese, String daySky, boolean isGround,
		String ilganChineseForJijanggan) {

		ManseryeokCalculationResponse.PillarElement.PillarElementBuilder builder =
			formatChineseToBuilder(chinese, daySky, isGround, ilganChineseForJijanggan);

		if (isGround) {
			String unseong = unseongCalculator.calculate(ilganChinese, chinese);
			if (unseong != null) {
				builder.unseong(unseong);
				builder.unseongDescription(unseongCalculator.getUnseongDescription(unseong));
			} else {
				log.warn("⚠️ 운성 계산 실패: 일간={}, 지지={}", ilganChinese, chinese);
			}
		}
		return builder.build();
	}

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
			builder.jijanggan(getJijangganInfo(chinese, ilganChinese));
		}

		return builder;
	}

	private ManseryeokCalculationResponse.PillarElement formatChinese(
		String chinese, String daySky, boolean isGround, String ilganChinese) {
		return formatChineseToBuilder(chinese, daySky, isGround, ilganChinese).build();
	}

	private SamjuResult convertBirthToSamju(String birthdayType, LocalDate birthday,
		LocalTime time, Boolean leapMonth) {
		LocalTime birthtime = time;
		boolean isYajasi = time != null && !time.isBefore(LocalTime.of(23, 30));

		log.debug("만세력 데이터 조회: birthdayType={}, birthday={}, leapMonth={}",
			birthdayType, birthday, leapMonth);

		Manse baseManse;
		if ("SOLAR".equals(birthdayType)) {
			baseManse = manseRepository.findBySolarDate(birthday)
				.orElseThrow(() -> new RuntimeException("해당 양력 날짜의 만세력 데이터를 찾을 수 없습니다."));
		} else {
			List<Manse> lunarCandidates = manseRepository.findAllByLunarDateOrderBySolarDateAsc(
				birthday);
			if (lunarCandidates.isEmpty()) {
				throw new RuntimeException("해당 음력 날짜의 만세력 데이터를 찾을 수 없습니다.");
			}

			if (lunarCandidates.size() == 1) {
				baseManse = lunarCandidates.get(0);
			} else {
				// leapMonth가 null이면 평달(false)로 기본 처리. 평달·윤달 두 행을 이미 받았으므로 다시 조회하지 않고 고른다.
				Boolean resolvedLeapMonth = (leapMonth != null) ? leapMonth : false;
				log.info("윤달 여부 결정: leapMonth={} -> resolvedLeapMonth={}", leapMonth, resolvedLeapMonth);
				baseManse = lunarCandidates.stream()
					.filter(candidate -> resolvedLeapMonth.equals(candidate.getLeapMonth()))
					.findFirst()
					.orElseThrow(() -> new IllegalArgumentException(
						"음력 날짜와 윤달 여부에 맞는 만세력 데이터를 찾을 수 없습니다."));
			}
		}

		LocalDate civilSolarDate = baseManse.getSolarDate();
		Manse dayManse = baseManse;
		if (isYajasi) {
			LocalDate shiftedDate = civilSolarDate.plusDays(1);
			dayManse = manseRepository.findBySolarDate(shiftedDate)
				.orElseThrow(() -> new RuntimeException("자시 보정 대상 날짜의 만세력 데이터를 찾을 수 없습니다."));
			log.debug("자시 처리: 일주 기준 날짜를 다음날로 보정 -> {}", shiftedDate);
		}

		Manse yearMonthManse = baseManse;
		boolean seasonBoundaryUncertain = false;
		if (baseManse.getSeason() != null && !baseManse.getSeason().isEmpty()) {
			// 절입일에 태어난 경우라 절입 시각의 날짜가 곧 생년월일이다.
			log.debug("절입일 처리: season={}, seasonStartTime={}", baseManse.getSeason(),
				baseManse.getSeasonStartTime());

			LocalDateTime seasonTime = baseManse.getSeasonStartTime();
			LocalDate solarDate = baseManse.getSolarDate();
			if (birthtime == null) {
				seasonBoundaryUncertain = true;
				log.info("출생시간 미입력 + 절입일: 연주/월주 경계 불확정");
			} else {
				LocalDateTime solarDatetime = LocalDateTime.of(solarDate, birthtime);

				if (solarDatetime.isBefore(seasonTime)) {
					log.info("절입시간 이전 출생: 이전 날짜 만세력 사용(월주 변경), 일주는 유지");
					yearMonthManse = manseRepository.findBySolarDate(solarDate.minusDays(1))
						.orElseThrow(() -> new RuntimeException("이전 날짜의 만세력 데이터를 찾을 수 없습니다"));
				}
			}
		}

		return SamjuResult.builder()
			.solarDate(civilSolarDate)
			.yearSky(yearMonthManse.getYearSky())
			.yearGround(yearMonthManse.getYearGround())
			.monthSky(yearMonthManse.getMonthSky())
			.monthGround(yearMonthManse.getMonthGround())
			.daySky(dayManse.getDaySky())
			.dayGround(dayManse.getDayGround())
			.seasonStartTime(baseManse.getSeasonStartTime() != null ?
				baseManse.getSeasonStartTime().toString() : null)
			.seasonBoundaryUncertain(seasonBoundaryUncertain)
			.build();
	}

	private boolean isRightDirection(String gender, String yearSky) {
		String normalizedGender = normalizeGender(gender);
		String minusPlus = sajuDataService.getMinusPlus().get(yearSky);

		if (minusPlus == null) {
			throw new RuntimeException("연간 " + yearSky + "의 음양 정보를 찾을 수 없습니다");
		}

		boolean result;
		if (("MALE".equals(normalizedGender) && "양".equals(minusPlus)) ||
			("FEMALE".equals(normalizedGender) && "음".equals(minusPlus))) {
			result = true;
		} else {
			result = false;
		}

		log.debug("대운 방향 판단: gender={}, yearSky={}, minusPlus={}, direction={}",
			normalizedGender, yearSky, minusPlus, result ? "순행" : "역행");

		return result;
	}

	private String normalizeGender(String gender) {
		if (gender == null || gender.isBlank()) {
			throw new IllegalArgumentException("성별(gender)은 필수입니다.");
		}

		String normalized = gender.trim().toUpperCase();
		return switch (normalized) {
			case "MALE", "M" -> "MALE";
			case "FEMALE", "F" -> "FEMALE";
			default -> throw new IllegalArgumentException("지원하지 않는 성별 값입니다: " + gender);
		};
	}

	private LocalDateTime getSeasonStartTime(boolean direction, LocalDateTime solarDatetime) {
		Manse manse;

		if (direction) {
			manse = manseRepository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(
					solarDatetime)
				.orElseThrow(() -> new RuntimeException("순행 절입 시간을 찾을 수 없습니다"));
		} else {
			manse = manseRepository.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(
					solarDatetime)
				.orElseThrow(() -> new RuntimeException("역행 절입 시간을 찾을 수 없습니다"));
		}

		// 태어난 때와 가장 가까운 절입 시각이라 생년월일을 한 달 안으로 좁혀 준다.
		log.debug("절입시간 조회 완료: seasonStartTime={}, direction={}",
			manse.getSeasonStartTime(), direction ? "순행" : "역행");

		return manse.getSeasonStartTime();
	}

	private BigFortuneRangeResult calculateBigFortuneRange(boolean direction, SamjuResult samju,
		LocalTime rawSolarTime, boolean timeUnknown, List<String> uncertaintyNotes) {
		if (!timeUnknown) {
			LocalDateTime solarDatetime = LocalDateTime.of(samju.getSolarDate(), rawSolarTime);
			LocalDateTime seasonTime = getSeasonStartTime(direction, solarDatetime);
			BigFortuneResult exact = getBigFortuneNumber(direction, seasonTime, solarDatetime);
			return BigFortuneRangeResult.builder()
				.bigFortuneNumber(exact.getBigFortuneNumber())
				.bigFortuneNumberMin(exact.getBigFortuneNumber())
				.bigFortuneNumberMax(exact.getBigFortuneNumber())
				.bigFortuneStart(exact.getBigFortuneStart())
				.bigFortuneStartMin(exact.getBigFortuneStart())
				.bigFortuneStartMax(exact.getBigFortuneStart())
				.build();
		}

		if (samju.isSeasonBoundaryUncertain()) {
			uncertaintyNotes.add("출생시간 미입력으로 대운 시작 나이는 확정할 수 없습니다.");
			return BigFortuneRangeResult.builder().build();
		}

		LocalDate birthDate = samju.getSolarDate();
		LocalDateTime startOfDay = LocalDateTime.of(birthDate, LocalTime.MIN);
		LocalDateTime endOfDay = LocalDateTime.of(birthDate, LocalTime.of(23, 59, 59));

		BigFortuneResult earlyCase = getBigFortuneNumber(direction,
			getSeasonStartTime(direction, startOfDay), startOfDay);
		BigFortuneResult lateCase = getBigFortuneNumber(direction,
			getSeasonStartTime(direction, endOfDay), endOfDay);

		int minNumber = Math.min(earlyCase.getBigFortuneNumber(), lateCase.getBigFortuneNumber());
		int maxNumber = Math.max(earlyCase.getBigFortuneNumber(), lateCase.getBigFortuneNumber());
		int minStart = Math.min(earlyCase.getBigFortuneStart(), lateCase.getBigFortuneStart());
		int maxStart = Math.max(earlyCase.getBigFortuneStart(), lateCase.getBigFortuneStart());

		if (minNumber != maxNumber || minStart != maxStart) {
			uncertaintyNotes.add(String.format("대운 시작 나이는 %d~%d세 범위입니다.", minNumber, maxNumber));
		} else {
			uncertaintyNotes.add(String.format("대운 시작 나이는 %d세로 추정됩니다.", minNumber));
		}

		return BigFortuneRangeResult.builder()
			.bigFortuneNumber(minNumber == maxNumber ? minNumber : null)
			.bigFortuneNumberMin(minNumber)
			.bigFortuneNumberMax(maxNumber)
			.bigFortuneStart(minStart == maxStart ? minStart : null)
			.bigFortuneStartMin(minStart)
			.bigFortuneStartMax(maxStart)
			.build();
	}

	private BigFortuneResult getBigFortuneNumber(boolean direction, LocalDateTime seasonStartTime,
		LocalDateTime solarDatetime) {
		long diffDays;

		if (direction) {
			diffDays = ChronoUnit.DAYS.between(solarDatetime, seasonStartTime);
		} else {
			diffDays = ChronoUnit.DAYS.between(seasonStartTime, solarDatetime);
		}

		if (diffDays < 4) {
			int bigFortuneNumber = 1;
			int bigFortuneStart = solarDatetime.getYear() + bigFortuneNumber;

			log.info("대운 계산 완료 (early return): diffDays={}, bigFortuneNumber={}, bigFortuneStart={}",
				diffDays, bigFortuneNumber, bigFortuneStart);

			return BigFortuneResult.builder()
				.bigFortuneNumber(bigFortuneNumber)
				.bigFortuneStart(bigFortuneStart)
				.build();
		}

		int divider = (int) (diffDays / 3);
		int remainder = (int) (diffDays % 3);

		int bigFortuneNumber = divider;
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
			log.debug("시주 계산 완료: daySky={}, time={}, timeKey={}, timeSky={}, timeGround={}",
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

		if (time.compareTo(LocalTime.of(23, 30)) >= 0 ||
			time.compareTo(LocalTime.of(1, 29)) <= 0) {
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

	@SuppressWarnings("unchecked")
	private ManseryeokCalculationResponse.JijangganElement createJijangganElement(
		Map<String, Object> elementData, String ilganChinese) {
		if (elementData == null) {
			return null;
		}

		String chinese = (String) elementData.get("chinese");

		String tenStar = null;
		if (chinese != null && ilganChinese != null) {
			Map<String, Map<String, String>> tenStarData = sajuDataService.getTenStar();
			Map<String, String> ilganTenStarMap = tenStarData.get(ilganChinese);
			if (ilganTenStarMap != null) {
				String tenStarInfo = ilganTenStarMap.get(chinese);
				if (tenStarInfo != null) {
					String[] parts = tenStarInfo.split(",");
					tenStar = parts.length > 0 ? parts[0] : null;
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
			.tenStar(tenStar)
			.build();
	}

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
		private boolean seasonBoundaryUncertain;
	}

	@lombok.Data
	@lombok.Builder
	private static class BigFortuneResult {

		private Integer bigFortuneNumber;
		private Integer bigFortuneStart;
	}

	@lombok.Data
	@lombok.Builder
	private static class BigFortuneRangeResult {

		private Integer bigFortuneNumber;
		private Integer bigFortuneNumberMin;
		private Integer bigFortuneNumberMax;
		private Integer bigFortuneStart;
		private Integer bigFortuneStartMin;
		private Integer bigFortuneStartMax;
	}

	@lombok.Data
	@lombok.Builder
	private static class TimePillarResult {

		private String timeSky;
		private String timeGround;
	}
}
