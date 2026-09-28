package com.mansereok.server.domain.interpret.service;

import com.mansereok.server.domain.interpret.calculator.DaewoonDirection;
import com.mansereok.server.domain.interpret.calculator.FiveElement;
import com.mansereok.server.domain.interpret.calculator.FourPillars;
import com.mansereok.server.domain.interpret.calculator.RelationCalculator;
import com.mansereok.server.domain.interpret.calculator.SinsalCalculator;
import com.mansereok.server.domain.interpret.calculator.UnseongCalculator;
import com.mansereok.server.domain.interpret.calculator.YongsinCalculator;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCalculationRequest;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.MonthlyFortune;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse.PillarElement;
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
import java.time.temporal.ChronoField;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Stream;
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
	private static final int JASI_START_MINUTE_OF_DAY = JASI_START.get(ChronoField.MINUTE_OF_DAY);
	private static final int MINUTES_PER_DAY = 24 * 60;
	private static final int MINUTES_PER_TIME_PILLAR = 2 * 60;
	// 만세력 표(manses)에 들어 있는 양력 날짜 범위. 표에서 날짜를 못 찾으면 입력 오류로 보고 이 범위를 알려 준다.
	// 범위 안이라도 가장자리 날짜는 계산이 실패할 수 있다. 표의 절입은 1900-01-06 04:08~2100-12-07 10:04 만 있어
	// 1900-01-06 04:08 전 출생의 역행 대운과 2100-12-07 10:04 이후 출생의 순행 대운은 셀 절입이 없고,
	// 2100-12-31 23:30 이후 출생은 야자시로 표에 없는 다음 날 일주가 필요하다.
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

			// 응답·프롬프트에 돌려주는 출생시각을 분 단위로 맞추고, 분 단위인 절입 시각과의 비교와 대운 날수 계산도 같은 단위로 하려고
			// 초를 버린다. 시주 번호는 시·분만 보므로 초가 남아도 시주가 빠지지는 않는다.
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

			SajuInfo sajuInfo = buildSajuInfo(samju, timePillar, daewoonDirection, bigFortune, uncertaintyNotes);
			// 용신은 기둥 칸과 지장간까지 채운 사주를 보고 정하므로 사주 정보를 다 채운 뒤에 넣는다.
			sajuInfo.setYongsinInfo(yongsinCalculator.analyzeYongsin(sajuInfo));

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

	/**
	 * 네 기둥과 대운 결과로 응답의 사주 정보를 채운다. 기둥 칸, 신살, 지지·천간 관계, 삼합, 월운까지 넣고 용신은 비워 둔다.
	 */
	private SajuInfo buildSajuInfo(SamjuResult samju, TimePillarResult timePillar,
		DaewoonDirection daewoonDirection, BigFortuneRangeResult bigFortune, List<String> uncertaintyNotes) {
		String dayStem = samju.getDaySky();

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
		boolean hasGoegang = sinsalCalculator.hasGoegang(dayStem, samju.getDayGround());
		boolean hasBaekho = sinsalCalculator.hasBaekho(dayStem, samju.getDayGround());
		List<String> gongmang = sinsalCalculator.calculateGongmang(dayStem, samju.getDayGround());
		List<String> groundRelations = analyzeGroundRelations(samju, timePillar);
		List<String> skyRelations = analyzeSkyRelations(samju, timePillar);
		List<String> fullSamhap = relationCalculator.findFullSamhap(
			Stream.of(samju.getYearGround(), samju.getMonthGround(), samju.getDayGround(),
					timePillar.getTimeGround())
				.filter(Objects::nonNull)
				.toList());

		return SajuInfo.builder()
			.bigFortuneNumber(bigFortune.getBigFortuneNumber())
			.bigFortuneNumberMin(bigFortune.getBigFortuneNumberMin())
			.bigFortuneNumberMax(bigFortune.getBigFortuneNumberMax())
			.bigFortuneStartYear(bigFortune.getBigFortuneStart())
			.bigFortuneStartYearMin(bigFortune.getBigFortuneStartMin())
			.bigFortuneStartYearMax(bigFortune.getBigFortuneStartMax())
			.daewoonDirection(daewoonDirection)
			.seasonStartTime(samju.getSeasonStartTime())
			.uncertaintyNotes(List.copyOf(uncertaintyNotes))
			.yearSky(stemElement(samju.getYearSky(), dayStem))
			.yearGround(branchElement(samju.getYearGround(), dayStem))
			.monthSky(stemElement(samju.getMonthSky(), dayStem))
			.monthGround(branchElement(samju.getMonthGround(), dayStem))
			.daySky(stemElement(dayStem, dayStem))
			.dayGround(branchElement(samju.getDayGround(), dayStem))
			.timeSky(timePillar.getTimeSky() != null ? stemElement(timePillar.getTimeSky(), dayStem) : null)
			.timeGround(timePillar.getTimeGround() != null ? branchElement(timePillar.getTimeGround(), dayStem)
				: null)
			.sinsalInfo(sinsalInfo)
			.hasGoegang(hasGoegang)
			.hasBaekho(hasBaekho)
			.gongmang(gongmang)
			.groundRelations(groundRelations)
			.skyRelations(skyRelations)
			.samhap(fullSamhap)
			.monthlyFortunes(calculateMonthlyFortunes(dayStem))
			.build();
	}

	/**
	 * 지지 여섯 짝의 충·원진·형·파·해·반합을 년지-월지, 년지-일지, 월지-일지, 년지-시지, 월지-시지, 일지-시지 순서로 적는다.
	 */
	private List<String> analyzeGroundRelations(SamjuResult samju, TimePillarResult timePillar) {
		String timeGround = timePillar.getTimeGround();
		return describeRelations(relationCalculator::analyzeRelation, List.of(
			new RelationPair("년지-월지", samju.getYearGround(), samju.getMonthGround()),
			new RelationPair("년지-일지", samju.getYearGround(), samju.getDayGround()),
			new RelationPair("월지-일지", samju.getMonthGround(), samju.getDayGround()),
			new RelationPair("년지-시지", samju.getYearGround(), timeGround),
			new RelationPair("월지-시지", samju.getMonthGround(), timeGround),
			new RelationPair("일지-시지", samju.getDayGround(), timeGround)));
	}

	/**
	 * 천간 여섯 짝의 천간합·천간충을 년간-월간, 년간-일간, 월간-일간, 년간-시간, 월간-시간, 일간-시간 순서로 적는다.
	 */
	private List<String> analyzeSkyRelations(SamjuResult samju, TimePillarResult timePillar) {
		String timeSky = timePillar.getTimeSky();
		return describeRelations(relationCalculator::analyzeSkyRelation, List.of(
			new RelationPair("년간-월간", samju.getYearSky(), samju.getMonthSky()),
			new RelationPair("년간-일간", samju.getYearSky(), samju.getDaySky()),
			new RelationPair("월간-일간", samju.getMonthSky(), samju.getDaySky()),
			new RelationPair("년간-시간", samju.getYearSky(), timeSky),
			new RelationPair("월간-시간", samju.getMonthSky(), timeSky),
			new RelationPair("일간-시간", samju.getDaySky(), timeSky)));
	}

	/**
	 * 짝마다 관계를 찾아 "년지-월지: 충, 형" 처럼 한 줄로 적는다. 관계가 없는 짝과, 시간을 몰라 시주 글자가 비어 있는 짝은 적지 않는다.
	 */
	private static List<String> describeRelations(BiFunction<String, String, List<String>> relationsOf,
		List<RelationPair> pairs) {
		List<String> lines = new ArrayList<>();
		for (RelationPair pair : pairs) {
			if (pair.left() == null || pair.right() == null) {
				continue;
			}
			List<String> relations = relationsOf.apply(pair.left(), pair.right());
			if (!relations.isEmpty()) {
				lines.add(pair.label() + ": " + String.join(", ", relations));
			}
		}
		return lines;
	}

	private List<MonthlyFortune> calculateMonthlyFortunes(String dayStem) {
		List<MonthlyFortune> monthlyFortunes = new ArrayList<>();
		LocalDateTime nowKst = LocalDateTime.now(clock.withZone(SEOUL));

		Manse currentBoundary = manseRepository
			.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(nowKst)
			.orElse(null);
		if (currentBoundary == null || currentBoundary.getSeasonStartTime() == null) {
			log.warn("월운 계산 실패: 현재 절입 기준점 조회 불가");
			return List.of();
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

			PillarElement monthSky = stemElement(startBoundary.getMonthSky(), dayStem);
			PillarElement monthGround = branchElement(startBoundary.getMonthGround(), dayStem);

			monthlyFortunes.add(MonthlyFortune.builder()
				.year(periodStart.getYear())
				.month(periodStart.getMonthValue())
				.season(startBoundary.getSeason())
				.periodStart(periodStart)
				.periodEnd(periodEnd)
				.monthSky(monthSky)
				.monthGround(monthGround)
				.build());
		}

		return List.copyOf(monthlyFortunes);
	}

	/**
	 * 천간 한 칸을 채운다. 한자, 한글 이름, 오행과 색, 일간 기준 십성, 음양을 넣는다.
	 */
	private PillarElement stemElement(String stem, String dayStem) {
		return pillarElementBuilder(stem, dayStem).build();
	}

	/**
	 * 지지 한 칸을 채운다. 천간 칸과 같은 값에 지장간과 일간 기준 12운성을 더한다.
	 */
	private PillarElement branchElement(String branch, String dayStem) {
		PillarElement.PillarElementBuilder builder = pillarElementBuilder(branch, dayStem)
			.jijanggan(getJijangganInfo(branch, dayStem));

		String unseong = unseongCalculator.calculate(dayStem, branch);
		if (unseong != null) {
			builder.unseong(unseong);
			builder.unseongDescription(unseongCalculator.getUnseongDescription(unseong));
		} else {
			log.warn("⚠️ 운성 계산 실패: 일간={}, 지지={}", dayStem, branch);
		}
		return builder.build();
	}

	/**
	 * 천간·지지 칸이 함께 쓰는 값(한자, 한글 이름, 오행과 색, 일간 기준 십성, 음양)을 채운 빌더.
	 */
	private PillarElement.PillarElementBuilder pillarElementBuilder(String chinese, String dayStem) {
		String tenStarInfo = sajuDataService.tenStarOf(dayStem, chinese);
		if (tenStarInfo == null) {
			throw new IllegalStateException("일간 " + dayStem + " 기준 간지 " + chinese + "의 십성 정보를 찾을 수 없습니다");
		}

		String[] tenStarParts = tenStarInfo.split(",");
		if (tenStarParts.length != 2) {
			throw new IllegalStateException("십성 정보 형식이 올바르지 않습니다: " + tenStarInfo);
		}

		return PillarElement.builder()
			.chinese(chinese)
			.korean(sajuDataService.koreanOf(chinese))
			.fiveCircle(tenStarParts[1])
			.fiveCircleColor(FiveElement.of(tenStarParts[1]).color())
			.tenStar(tenStarParts[0])
			.minusPlus(sajuDataService.yinYangOf(chinese));
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
				// 절입일에 태어났다는 사실도 생년월일 후보를 절입일로 좁히므로 DEBUG 로 남긴다. 아래 절입시간 이전 출생 줄도 같다.
				log.debug("출생시간 미입력 + 절입일: 연주/월주 경계 불확정");
			} else {
				LocalDateTime solarDatetime = LocalDateTime.of(solarDate, birthtime);

				if (solarDatetime.isBefore(seasonTime)) {
					log.debug("절입시간 이전 출생: 이전 날짜 만세력 사용(월주 변경), 일주는 유지");
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
			// 입력값은 메시지에 넣지 않는다. 위 calculate 가 메시지를 경고 로그에 그대로 남기기 때문이다.
			default -> throw new IllegalArgumentException("지원하지 않는 성별 값입니다.");
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

			// 대운 시작 해에서 대운수를 빼면 출생 연도가 나오므로 DEBUG 로 남긴다.
			log.debug("대운 계산 완료 (early return): diffDays={}, bigFortuneNumber={}, bigFortuneStart={}",
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

		// 대운 시작 해에서 대운수를 빼면 출생 연도가 나오므로 DEBUG 로 남긴다.
		log.debug("대운 계산 완료: diffDays={}, bigFortuneNumber={}, bigFortuneStart={}",
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
		int minutesSinceJasiStart = Math.floorMod(time.get(ChronoField.MINUTE_OF_DAY) - JASI_START_MINUTE_OF_DAY,
			MINUTES_PER_DAY);
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

	/**
	 * 관계를 볼 두 글자와, 결과 줄 앞에 붙일 "년지-월지" 같은 이름.
	 */
	private record RelationPair(String label, String left, String right) {

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
