package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.domain.interpret.calculator.DaewoonDirection;
import com.mansereok.server.domain.interpret.calculator.FiveElement;
import com.mansereok.server.domain.interpret.calculator.FourPillars;
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
import com.mansereok.server.domain.interpret.service.SajuDataService.HiddenStem;
import com.mansereok.server.domain.interpret.service.SajuDataService.HiddenStems;
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
	// 자시가 시작하는 시각. 이때부터 일주를 다음 날로 넘기고(야자시), 시주도 이 시각부터 2시간씩 자·축·인… 순서로 센다.
	private static final LocalTime JASI_START = LocalTime.of(23, 30);
	private static final int MINUTES_PER_DAY = 24 * 60;
	private static final int MINUTES_PER_TIME_PILLAR = 2 * 60;
	// 만세력 표(manses)에 들어 있는 양력 날짜 범위. 표에서 날짜를 못 찾으면 입력 오류로 보고 이 범위를 알려 준다.
	private static final String SUPPORTED_RANGE = "지원 범위(양력 1900-01-01~2100-12-31)";

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

			// 시주 구간과 절입 시각이 분 단위라 초는 버린다. 초를 남기면 hh:29:30 같은 시각이 어느 시주에도 들지 않는다.
			LocalTime rawSolarTime = request.getSolarTime() == null ? null
				: request.getSolarTime().truncatedTo(ChronoUnit.MINUTES);
			boolean timeUnknown = rawSolarTime == null;
			List<String> uncertaintyNotes = new ArrayList<>();
			if (timeUnknown) {
				uncertaintyNotes.add("출생시간 미입력: 시주는 계산하지 않았습니다.");
				uncertaintyNotes.add("출생시간 미입력: 야자시(23:30 이후) 보정은 적용하지 않았습니다.");
			}

			SamjuResult samju = convertBirthToSamju(
				CalendarType.of(request.getIsLunar()),
				request.getSolarDate(),
				rawSolarTime,
				request.getLeapMonth()
			);
			if (samju.isSeasonBoundaryUncertain()) {
				uncertaintyNotes.add("절입일 출생 + 시간 미입력으로 연주/월주 경계가 불확정입니다.");
			}

			DaewoonDirection daewoonDirection = decideDaewoonDirection(request.getGender(), samju.getYearSky());
			BigFortuneRangeResult bigFortune = calculateBigFortuneRange(daewoonDirection, samju, rawSolarTime,
				timeUnknown, uncertaintyNotes);
			TimePillarResult timePillar = getTimePillar(samju.getDaySky(), rawSolarTime);
			String ilganChinese = samju.getDaySky();

			Map<String, List<String>> sinsalInfo = sinsalCalculator.analyzeAllSinsal(new FourPillars(
				samju.getYearSky(),
				samju.getYearGround(),
				samju.getMonthSky(),
				samju.getMonthGround(),
				samju.getDaySky(),
				samju.getDayGround(),
				timePillar.getTimeSky(),
				timePillar.getTimeGround()
			));
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
				.daewoonDirection(daewoonDirection)
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

		String tenStarInfo = sajuDataService.tenStarOf(daySky, chinese);
		if (tenStarInfo == null) {
			throw new IllegalStateException("일간 " + daySky + " 기준 간지 " + chinese + "의 십성 정보를 찾을 수 없습니다");
		}

		String[] tenStarParts = tenStarInfo.split(",");
		if (tenStarParts.length != 2) {
			throw new IllegalStateException("십성 정보 형식이 올바르지 않습니다: " + tenStarInfo);
		}

		ManseryeokCalculationResponse.PillarElement.PillarElementBuilder builder =
			ManseryeokCalculationResponse.PillarElement.builder()
				.chinese(chinese)
				.korean(sajuDataService.koreanOf(chinese))
				.fiveCircle(tenStarParts[1])
				.fiveCircleColor(FiveElement.of(tenStarParts[1]).color())
				.tenStar(tenStarParts[0])
				.minusPlus(sajuDataService.yinYangOf(chinese));

		if (isGround) {
			builder.jijanggan(getJijangganInfo(chinese, ilganChinese));
		}

		return builder;
	}

	private ManseryeokCalculationResponse.PillarElement formatChinese(
		String chinese, String daySky, boolean isGround, String ilganChinese) {
		return formatChineseToBuilder(chinese, daySky, isGround, ilganChinese).build();
	}

	/**
	 * 생년월일로 만세력 표를 찾아 연주·월주·일주를 정한다. 표에 없는 날짜는 사용자가 고칠 입력이라 IllegalArgumentException(400)
	 * 으로 알린다. 날짜는 개인정보라 메시지에 넣지 않는다(메시지가 경고 로그에 남는다).
	 */
	private SamjuResult convertBirthToSamju(CalendarType calendarType, LocalDate birthday,
		LocalTime time, Boolean leapMonth) {
		LocalTime birthtime = time;
		boolean isYajasi = time != null && !time.isBefore(JASI_START);

		log.debug("만세력 데이터 조회: calendarType={}, birthday={}, leapMonth={}",
			calendarType, birthday, leapMonth);

		Manse baseManse;
		if (calendarType == CalendarType.SOLAR) {
			baseManse = manseRepository.findBySolarDate(birthday)
				.orElseThrow(() -> new IllegalArgumentException(
					SUPPORTED_RANGE + " 밖이거나 존재하지 않는 날짜입니다."));
		} else {
			List<Manse> lunarCandidates = manseRepository.findAllByLunarDateOrderBySolarDateAsc(
				birthday);
			if (lunarCandidates.isEmpty()) {
				throw new IllegalArgumentException(SUPPORTED_RANGE + " 밖이거나 존재하지 않는 음력 날짜입니다.");
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
				.orElseThrow(() -> new IllegalArgumentException(
					"23:30 이후 출생은 다음 날로 일주를 세는데, 다음 날이 " + SUPPORTED_RANGE + " 밖입니다."));
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
						.orElseThrow(() -> new IllegalArgumentException(
							"절입 시각 전 출생은 전날로 연주·월주를 세는데, 전날이 " + SUPPORTED_RANGE + " 밖입니다."));
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

	/**
	 * 대운 방향을 정한다. 양간 해의 남자와 음간 해의 여자는 순행, 그 밖은 역행이다. 이 값을 응답에 실어 프롬프트가 다시 계산하지 않게
	 * 한다.
	 */
	private DaewoonDirection decideDaewoonDirection(String gender, String yearSky) {
		String normalizedGender = normalizeGender(gender);
		String minusPlus = sajuDataService.yinYangOf(yearSky);

		if (minusPlus == null) {
			throw new IllegalStateException("연간 " + yearSky + "의 음양 정보를 찾을 수 없습니다");
		}

		DaewoonDirection direction;
		if (("MALE".equals(normalizedGender) && "양".equals(minusPlus)) ||
			("FEMALE".equals(normalizedGender) && "음".equals(minusPlus))) {
			direction = DaewoonDirection.FORWARD;
		} else {
			direction = DaewoonDirection.BACKWARD;
		}

		log.debug("대운 방향 판단: gender={}, yearSky={}, minusPlus={}, direction={}",
			normalizedGender, yearSky, minusPlus, direction.label());

		return direction;
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

	private LocalDateTime getSeasonStartTime(DaewoonDirection direction, LocalDateTime solarDatetime) {
		Manse manse = switch (direction) {
			case FORWARD -> manseRepository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(
					solarDatetime)
				.orElseThrow(() -> new IllegalArgumentException(
					"만세력 표에 출생 뒤의 절입이 없어 대운을 셀 수 없는 생년월일입니다."));
			case BACKWARD -> manseRepository.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(
					solarDatetime)
				.orElseThrow(() -> new IllegalArgumentException(
					"만세력 표에 출생 전의 절입이 없어 대운을 셀 수 없는 생년월일입니다."));
		};

		// 태어난 때와 가장 가까운 절입 시각이라 생년월일을 한 달 안으로 좁혀 준다.
		log.debug("절입시간 조회 완료: seasonStartTime={}, direction={}",
			manse.getSeasonStartTime(), direction.label());

		return manse.getSeasonStartTime();
	}

	private BigFortuneRangeResult calculateBigFortuneRange(DaewoonDirection direction, SamjuResult samju,
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

	private BigFortuneResult getBigFortuneNumber(DaewoonDirection direction, LocalDateTime seasonStartTime,
		LocalDateTime solarDatetime) {
		long diffDays = switch (direction) {
			case FORWARD -> ChronoUnit.DAYS.between(solarDatetime, seasonStartTime);
			case BACKWARD -> ChronoUnit.DAYS.between(seasonStartTime, solarDatetime);
		};

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

		// 시 번호는 늘 0~11 이라, 표에서 못 찾는 경우는 일간이 표에 없을 때뿐이다.
		int timeKey = getTimeJuIndex(time);
		List<String> timeJu = sajuDataService.timePillarOf(daySky, timeKey);
		if (timeJu == null) {
			throw new IllegalStateException("일간 " + daySky + "의 시주 데이터를 찾을 수 없습니다");
		}

		log.debug("시주 계산 완료: daySky={}, time={}, timeKey={}, timeSky={}, timeGround={}",
			daySky, time, timeKey, timeJu.get(0), timeJu.get(1));

		return TimePillarResult.builder()
			.timeSky(timeJu.get(0))
			.timeGround(timeJu.get(1))
			.build();
	}

	/**
	 * 출생 시각이 드는 시주 번호(0 자시 ~ 11 해시)를 센다. 자시 시작(23:30)부터 지난 분을 2시간으로 나눈 몫이라 하루의 모든 분이
	 * 빈틈없이 한 번호에 든다. 자정을 넘는 자시(23:30~01:29)도 따로 다루지 않는다. 초는 보지 않는다.
	 */
	private static int getTimeJuIndex(LocalTime time) {
		int minuteOfDay = time.getHour() * 60 + time.getMinute();
		int jasiStartMinuteOfDay = JASI_START.getHour() * 60 + JASI_START.getMinute();
		int minutesSinceJasiStart = Math.floorMod(minuteOfDay - jasiStartMinuteOfDay, MINUTES_PER_DAY);
		return minutesSinceJasiStart / MINUTES_PER_TIME_PILLAR;
	}

	private ManseryeokCalculationResponse.JijangganInfo getJijangganInfo(String jiji,
		String ilganChinese) {
		HiddenStems hiddenStems = sajuDataService.hiddenStemsOf(jiji);
		if (hiddenStems == null) {
			return null;
		}

		return ManseryeokCalculationResponse.JijangganInfo.builder()
			.first(createJijangganElement(hiddenStems.first(), ilganChinese))
			.second(createJijangganElement(hiddenStems.second(), ilganChinese))
			.third(createJijangganElement(hiddenStems.third(), ilganChinese))
			.build();
	}

	private ManseryeokCalculationResponse.JijangganElement createJijangganElement(
		HiddenStem hiddenStem, String ilganChinese) {
		if (hiddenStem == null) {
			return null;
		}

		String tenStar = null;
		String tenStarInfo = sajuDataService.tenStarOf(ilganChinese, hiddenStem.chinese());
		if (tenStarInfo != null) {
			tenStar = tenStarInfo.split(",")[0];
		}

		return ManseryeokCalculationResponse.JijangganElement.builder()
			.chinese(hiddenStem.chinese())
			.korean(hiddenStem.korean())
			.fiveCircle(hiddenStem.fiveCircle())
			.fiveCircleColor(FiveElement.of(hiddenStem.fiveCircle()).color())
			.minusPlus(hiddenStem.minusPlus())
			.rate(hiddenStem.rate())
			.tenStar(tenStar)
			.build();
	}

	/**
	 * 입력 생년월일이 양력인지 음력인지. 만세력 표를 양력 날짜로 찾을지 음력 날짜로 찾을지를 가른다.
	 */
	private enum CalendarType {
		SOLAR, LUNAR;

		/**
		 * 요청의 isLunar 를 바꾼다. 비어 있으면 양력으로 짐작하지 않고 입력 오류로 돌려보낸다. 짐작이 틀리면 다른 사주가 나온다.
		 *
		 * @throws IllegalArgumentException isLunar 가 null 일 때
		 */
		static CalendarType of(Boolean isLunar) {
			if (isLunar == null) {
				throw new IllegalArgumentException("양력·음력 여부는 필수입니다.");
			}
			return isLunar ? LUNAR : SOLAR;
		}
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
