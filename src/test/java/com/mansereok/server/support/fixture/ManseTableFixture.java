package com.mansereok.server.support.fixture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.mansereok.server.domain.interpret.entity.Manse;
import com.mansereok.server.domain.interpret.repository.ManseRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import org.mockito.Mockito;
import org.mockito.quality.Strictness;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;

/**
 * 만세력 표(manses)를 메모리에 올려, 목 ManseRepository 의 조회 메서드가 DB 대신 이 표로 답하게 한다.
 *
 * <p>{@link #realTable()} 은 운영 DB 에 넣는 실데이터 src/main/resources/data/manses.sql(약 9MB, 73,414행)을 JVM 에서 처음
 * 부를 때 한 번만 읽고, 그 뒤로는 같은 색인을 돌려준다. 표는 읽기 전용이라 테스트끼리 나눠 써도 값이 섞이지 않는다.
 * {@link #of(Manse...)} 는 손으로 만든 몇 행으로 같은 모양의 표를 만든다.
 *
 * <p>DB 와 같은 답을 내도록 다음을 맞춘다.
 * <ul>
 *   <li>lunar_date 가 '0000-00-00' 인 행(음력 2월 29·30일처럼 양력 날짜로 쓸 수 없는 날)은 JDBC 설정
 *   zeroDateTimeBehavior=CONVERT_TO_NULL 이 읽는 것처럼 음력 날짜를 null 로 둔다.</li>
 *   <li>절입 조회는 season_start_time 이 있는 행만 대상으로 하고, 같은 시각을 넣는지 빼는지는 메서드 이름(GreaterThanEqual,
 *   GreaterThan, LessThanEqual)대로 가른다.</li>
 * </ul>
 *
 * <p>Manse 는 생성자가 protected 라 리플렉션이 필요한데, 그 우회를 이 클래스 한 곳에만 둔다. 손으로 만든 행이 필요한 테스트도
 * {@link #manse} 를 쓴다.
 */
public final class ManseTableFixture {

	private static final String CLASSPATH_LOCATION = "data/manses.sql";
	private static final String INSERT_PREFIX = "INSERT INTO `manses` VALUES ";
	// 덤프의 INSERT 는 컬럼 목록 없이 이 순서로 값을 적는다.
	// id, solar_date, lunar_date, season, season_start_time, leap_month, year_sky, year_ground,
	// month_sky, month_ground, day_sky, day_ground, created_at, updated_at
	private static final int COLUMN_COUNT = 14;
	private static final String ZERO_DATE = "0000-00-00";
	private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private final List<Manse> rows;
	private final Map<LocalDate, Manse> bySolarDate = new HashMap<>();
	private final Map<LocalDate, List<Manse>> byLunarDate = new HashMap<>();
	private final NavigableMap<LocalDateTime, Manse> bySeasonStartTime = new TreeMap<>();

	private ManseTableFixture(List<Manse> rows) {
		this.rows = List.copyOf(rows);
		for (Manse row : this.rows) {
			putOnce(bySolarDate, row.getSolarDate(), row, "양력 날짜");
			if (row.getLunarDate() != null) {
				byLunarDate.computeIfAbsent(row.getLunarDate(), date -> new ArrayList<>()).add(row);
			}
			if (row.getSeasonStartTime() != null) {
				putOnce(bySeasonStartTime, row.getSeasonStartTime(), row, "절입 시각");
			}
		}
		// DB 의 findAllByLunarDateOrderBySolarDateAsc 와 같은 순서로 둔다.
		byLunarDate.values().forEach(list -> list.sort(Comparator.comparing(Manse::getSolarDate)));
	}

	/**
	 * 실데이터 manses.sql 로 만든 표. JVM 에서 처음 부를 때 한 번만 읽고, 그 뒤로는 같은 객체를 돌려준다.
	 */
	public static ManseTableFixture realTable() {
		return RealTableHolder.TABLE;
	}

	/**
	 * 손으로 만든 행으로 만든 표. 절입 경계 몇 개만으로 월운처럼 여러 번 조회하는 계산을 확인할 때 쓴다.
	 */
	public static ManseTableFixture of(Manse... rows) {
		return new ManseTableFixture(List.of(rows));
	}

	/**
	 * 손으로 만든 만세력 한 행. 음력 날짜는 양력 날짜와 같게 두고 윤달이 아닌 것으로 둔다.
	 *
	 * @param season 절기 이름. 절입일이 아니면 null
	 * @param seasonStartTime 절입 시각. 절입일이 아니면 null
	 */
	public static Manse manse(LocalDate solarDate, String yearSky, String yearGround, String monthSky,
		String monthGround, String daySky, String dayGround, String season, LocalDateTime seasonStartTime) {
		Manse manse = BeanUtils.instantiateClass(Manse.class);
		manse.setSolarDate(solarDate);
		manse.setLunarDate(solarDate);
		manse.setLeapMonth(false);
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

	/** 표의 모든 행. 실데이터는 덤프에 적힌 순서(양력 날짜 순)다. */
	public List<Manse> rows() {
		return rows;
	}

	/**
	 * 만세력 계산이 쓰는 조회 메서드 여섯 개가 이 표로 답하는 새 목 저장소. 부를 때마다 새 목을 만들어 테스트끼리 호출 기록이
	 * 섞이지 않는다.
	 *
	 * <p>이 목은 쓰지 않는 스텁을 실패로 보는 검사(strict stubs)를 끈다. 표 전체를 흉내 내는 스텁이라 테스트마다 부르는 조회가
	 * 다르기 때문이다(양력으로 입력하면 음력 조회를 부르지 않는다). 인자가 틀리면 표가 다른 행을 돌려주므로 계산 결과 단언에서
	 * 드러난다.
	 */
	public ManseRepository newRepository() {
		ManseRepository repository = Mockito.mock(ManseRepository.class,
			withSettings().strictness(Strictness.LENIENT));
		when(repository.findBySolarDate(any()))
			.thenAnswer(call -> Optional.ofNullable(bySolarDate.get(call.<LocalDate>getArgument(0))));
		when(repository.findAllByLunarDateOrderBySolarDateAsc(any()))
			.thenAnswer(call -> List.copyOf(lunarRows(call.getArgument(0))));
		when(repository.findByLunarDateAndLeapMonth(any(), any()))
			.thenAnswer(call -> findByLunarDateAndLeapMonth(call.getArgument(0), call.getArgument(1)));
		when(repository.findFirstBySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(any()))
			.thenAnswer(call -> valueOf(bySeasonStartTime.ceilingEntry(call.getArgument(0))));
		when(repository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(any()))
			.thenAnswer(call -> valueOf(bySeasonStartTime.higherEntry(call.getArgument(0))));
		when(repository.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(any()))
			.thenAnswer(call -> valueOf(bySeasonStartTime.floorEntry(call.getArgument(0))));
		return repository;
	}

	private List<Manse> lunarRows(LocalDate lunarDate) {
		return byLunarDate.getOrDefault(lunarDate, Collections.emptyList());
	}

	/**
	 * Spring Data 의 단건 조회처럼 두 행 이상이 맞으면 실패한다.
	 */
	private Optional<Manse> findByLunarDateAndLeapMonth(LocalDate lunarDate, Boolean leapMonth) {
		List<Manse> matches = lunarRows(lunarDate).stream()
			.filter(row -> Objects.equals(row.getLeapMonth(), leapMonth))
			.toList();
		if (matches.size() > 1) {
			throw new IllegalStateException("음력 " + lunarDate + ", 윤달 " + leapMonth + " 에 맞는 행이 "
				+ matches.size() + "개다");
		}
		return matches.stream().findFirst();
	}

	private static Optional<Manse> valueOf(Map.Entry<LocalDateTime, Manse> entry) {
		return entry == null ? Optional.empty() : Optional.of(entry.getValue());
	}

	private static <K> void putOnce(Map<K, Manse> index, K key, Manse row, String keyName) {
		Manse previous = index.putIfAbsent(key, row);
		if (previous != null) {
			throw new IllegalStateException(keyName + " " + key + " 인 행이 둘 이상이다. DB 조회는 어느 행을 줄지 정해지지 않는다");
		}
	}

	private static ManseTableFixture loadRealTable() {
		String dump = readDump();
		List<Manse> rows = new ArrayList<>();
		int statementStart = dump.indexOf(INSERT_PREFIX);
		while (statementStart >= 0) {
			int valuesStart = statementStart + INSERT_PREFIX.length();
			int valuesEnd = parseRows(dump, valuesStart, rows);
			statementStart = dump.indexOf(INSERT_PREFIX, valuesEnd);
		}
		return new ManseTableFixture(rows);
	}

	private static String readDump() {
		try (InputStream in = new ClassPathResource(CLASSPATH_LOCATION).getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(CLASSPATH_LOCATION + " 를 읽지 못했다", e);
		}
	}

	/**
	 * {@code (값,...),(값,...);} 를 문장 끝(;)까지 읽어 행으로 바꾸고, 문장 끝 다음 위치를 돌려준다. 작은따옴표 안의 쉼표·괄호는
	 * 글자로 보고, 백슬래시 이스케이프는 MySQL 덤프 형식대로 읽는다.
	 */
	private static int parseRows(String dump, int from, List<Manse> rows) {
		int position = from;
		List<String> values = new ArrayList<>();
		StringBuilder value = new StringBuilder();
		boolean inQuotes = false;
		boolean quoted = false;
		while (position < dump.length()) {
			char c = dump.charAt(position++);
			if (inQuotes) {
				if (c == '\\') {
					value.append(dump.charAt(position++));
				} else if (c == '\'') {
					inQuotes = false;
				} else {
					value.append(c);
				}
				continue;
			}
			switch (c) {
				case '\'' -> {
					inQuotes = true;
					quoted = true;
				}
				case '(' -> {
					values.clear();
					value.setLength(0);
					quoted = false;
				}
				case ',' -> {
					if (!values.isEmpty() || !value.isEmpty() || quoted) {
						values.add(toValue(value, quoted));
						value.setLength(0);
						quoted = false;
					}
				}
				case ')' -> {
					values.add(toValue(value, quoted));
					rows.add(toManse(values));
					values = new ArrayList<>();
					value.setLength(0);
					quoted = false;
				}
				case ';' -> {
					return position;
				}
				default -> value.append(c);
			}
		}
		throw new IllegalStateException(CLASSPATH_LOCATION + " 의 INSERT 문이 ; 로 끝나지 않는다");
	}

	/** 따옴표 없는 NULL 은 null 로, 나머지는 글자 그대로 둔다. */
	private static String toValue(StringBuilder value, boolean quoted) {
		String text = value.toString();
		return !quoted && "NULL".equals(text) ? null : text;
	}

	private static Manse toManse(List<String> values) {
		if (values.size() != COLUMN_COUNT) {
			throw new IllegalStateException("manses 행의 값이 " + COLUMN_COUNT + "개가 아니다: " + values);
		}
		Manse manse = BeanUtils.instantiateClass(Manse.class);
		manse.setId(Long.valueOf(values.get(0)));
		manse.setSolarDate(LocalDate.parse(values.get(1)));
		manse.setLunarDate(toDate(values.get(2)));
		manse.setSeason(values.get(3));
		manse.setSeasonStartTime(toDateTime(values.get(4)));
		manse.setLeapMonth(values.get(5) == null ? null : "1".equals(values.get(5)));
		manse.setYearSky(values.get(6));
		manse.setYearGround(values.get(7));
		manse.setMonthSky(values.get(8));
		manse.setMonthGround(values.get(9));
		manse.setDaySky(values.get(10));
		manse.setDayGround(values.get(11));
		manse.setCreatedAt(toDateTime(values.get(12)));
		manse.setUpdatedAt(toDateTime(values.get(13)));
		return manse;
	}

	private static LocalDate toDate(String value) {
		return value == null || value.startsWith(ZERO_DATE) ? null : LocalDate.parse(value);
	}

	private static LocalDateTime toDateTime(String value) {
		return value == null || value.startsWith(ZERO_DATE) ? null : LocalDateTime.parse(value, DATETIME);
	}

	/** 처음 {@link #realTable()} 을 부를 때 JVM 이 이 클래스를 한 번만 초기화하므로 덤프도 한 번만 읽는다. */
	private static final class RealTableHolder {

		private static final ManseTableFixture TABLE = loadRealTable();
	}
}
