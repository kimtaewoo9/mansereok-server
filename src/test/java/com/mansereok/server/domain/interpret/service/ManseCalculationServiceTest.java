package com.mansereok.server.domain.interpret.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.mansereok.server.domain.interpret.calculator.RelationCalculator;
import com.mansereok.server.domain.interpret.calculator.SinsalCalculator;
import com.mansereok.server.domain.interpret.calculator.UnseongCalculator;
import com.mansereok.server.domain.interpret.calculator.YongsinCalculator;
import com.mansereok.server.domain.interpret.dto.request.ManseryeokCalculationRequest;
import com.mansereok.server.domain.interpret.dto.response.ManseryeokCalculationResponse;
import com.mansereok.server.domain.interpret.entity.Manse;
import com.mansereok.server.domain.interpret.repository.ManseRepository;
import java.lang.reflect.Constructor;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManseCalculationServiceTest {

	@Mock
	private ManseRepository manseRepository;

	private ManseCalculationService service;

	@BeforeEach
	void setUp() {
		service = new ManseCalculationService(
			manseRepository,
			new SajuDataService(),
			new UnseongCalculator(),
			new SinsalCalculator(),
			new RelationCalculator(),
			new YongsinCalculator()
		);
	}

	@Test
	void shouldSkipTimePillarWhenSolarTimeIsMissing() {
		LocalDate inputDate = LocalDate.of(1990, 1, 1);
		Manse base = manse(inputDate, "庚", "午", "戊", "子", "甲", "子", null, null);
		Manse seasonRef = manse(
			LocalDate.of(1990, 1, 4),
			"庚", "午", "戊", "子", "丁", "卯",
			"소한",
			LocalDateTime.of(1990, 1, 4, 0, 0)
		);

		when(manseRepository.findBySolarDate(inputDate)).thenReturn(Optional.of(base));
		when(manseRepository.findFirstBySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(
			any(LocalDateTime.class))).thenReturn(Optional.of(seasonRef));

		ManseryeokCalculationRequest request = new ManseryeokCalculationRequest(
			"테스트",
			inputDate,
			null,
			"MALE",
			false,
			null
		);

		ManseryeokCalculationResponse response = service.calculate(request);

		assertNull(response.getInput().getSolarTime());
		assertTrue(response.getInput().getTimeUnknown());
		assertNull(response.getSaju().getTimeSky());
		assertNull(response.getSaju().getTimeGround());
		assertNotNull(response.getSaju().getUncertaintyNotes());
		assertTrue(response.getSaju().getUncertaintyNotes().stream()
			.anyMatch(note -> note.contains("출생시간 미입력")));
		assertNotNull(response.getSaju().getBigFortuneNumberMin());
		assertNotNull(response.getSaju().getBigFortuneNumberMax());
	}

	@Test
	void shouldApplyYajasiShiftAfterLunarToSolarConversion() {
		LocalDate lunarDate = LocalDate.of(1990, 1, 1);
		LocalDate civilSolarDate = LocalDate.of(1990, 1, 27);

		Manse lunarMapped = manse(civilSolarDate, "庚", "午", "己", "丑", "甲", "子", null, null);
		Manse nextDay = manse(civilSolarDate.plusDays(1), "庚", "午", "己", "丑", "乙", "丑", null,
			null);
		Manse seasonRef = manse(
			LocalDate.of(1990, 1, 31),
			"庚", "午", "庚", "寅", "戊", "午",
			"입춘",
			LocalDateTime.of(1990, 1, 31, 0, 0)
		);

		when(manseRepository.findAllByLunarDateOrderBySolarDateAsc(lunarDate))
			.thenReturn(List.of(lunarMapped));
		when(manseRepository.findBySolarDate(civilSolarDate.plusDays(1)))
			.thenReturn(Optional.of(nextDay));
		when(manseRepository.findFirstBySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(
			any(LocalDateTime.class))).thenReturn(Optional.of(seasonRef));

		ManseryeokCalculationRequest request = new ManseryeokCalculationRequest(
			"테스트",
			lunarDate,
			LocalTime.of(23, 40),
			"MALE",
			true,
			false
		);

		ManseryeokCalculationResponse response = service.calculate(request);

		assertEquals("庚", response.getSaju().getYearSky().getChinese());
		assertEquals("己", response.getSaju().getMonthSky().getChinese());
		assertEquals("乙", response.getSaju().getDaySky().getChinese());
		assertEquals("丑", response.getSaju().getDayGround().getChinese());
	}

	private Manse manse(
		LocalDate solarDate,
		String yearSky,
		String yearGround,
		String monthSky,
		String monthGround,
		String daySky,
		String dayGround,
		String season,
		LocalDateTime seasonStartTime
	) {
		Manse manse = newManse();
		manse.setSolarDate(solarDate);
		manse.setLunarDate(solarDate);
		manse.setYearSky(yearSky);
		manse.setYearGround(yearGround);
		manse.setMonthSky(monthSky);
		manse.setMonthGround(monthGround);
		manse.setDaySky(daySky);
		manse.setDayGround(dayGround);
		manse.setSeason(season);
		manse.setSeasonStartTime(seasonStartTime);
		return manse;
	}

	private Manse newManse() {
		try {
			Constructor<Manse> constructor = Manse.class.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (Exception e) {
			throw new RuntimeException("테스트용 Manse 인스턴스 생성 실패", e);
		}
	}
}
