package com.mansereok.server.support.fixture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.quality.Strictness;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 만세력 표(manses)를 메모리에 올려, 목 ManseRepository 의 조회 메서드가 DB 대신 이 표로 답하게 한다.
 *
 * <p>{@link #realTable()} 은 운영 DB 에 넣는 실데이터 src/main/resources/data/manses.sql(약 9MB, 73,414행)을 JVM 에서 처음
 * 부를 때 한 번만 읽고, 그 뒤로는 같은 색인을 돌려준다. {@link #of(Manse...)} 는 손으로 만든 몇 행으로 같은 모양의 표를 만든다.
 *
 * <p>Manse 에는 setter 가 없어 테스트가 돌려받은 행을 바꿀 수 없다. 그래서 표는 행을 복사하지 않고 그대로 내주며, 목록은 바꿀 수
 * 없는 목록으로 내준다. JVM 이 나눠 쓰는 실데이터 표는 어느 테스트에서도 바뀌지 않는다.
 *
 * <p>DB 와 같은 답을 내도록 다음을 맞춘다.
 * <ul>
 *   <li>lunar_date 가 '0000-00-00' 인 행(음력 2월 29·30일처럼 양력 날짜로 쓸 수 없는 날)은 JDBC 설정
 *   zeroDateTimeBehavior=CONVERT_TO_NULL 이 읽는 것처럼 음력 날짜를 null 로 둔다.</li>
 *   <li>절입 조회는 season_start_time 이 있는 행만 대상으로 하고, 같은 시각을 넣는지 빼는지는 메서드 이름(GreaterThanEqual,
 *   GreaterThan, LessThanEqual)대로 가른다. findTop13 은 DB 의 LIMIT 13 처럼 13개까지만 준다.</li>
 * </ul>
 *
 * <p>Manse 는 생성자가 protected 이고 setter 가 없어 리플렉션이 필요한데, 그 우회를 이 클래스 한 곳에만 둔다. 손으로 만든 행이
 * 필요한 테스트도 {@link ManseRow} 를 쓴다.
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
	// 월운이 한 번에 읽는 절입 수. ManseRepository.findTop13... 의 13 과 같다.
	private static final int MONTHLY_SEASON_LIMIT = 13;

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
		// DB 의 findAllByLunarDateOrderBySolarDateAsc 와 같은 순서로 두고, 조회 답으로 그대로 내주므로 바꿀 수 없는 목록으로 바꾼다.
		byLunarDate.replaceAll((lunarDate, sameLunarDate) -> sameLunarDate.stream()
			.sorted(Comparator.comparing(Manse::getSolarDate))
			.toList());
	}

	/**
	 * 실데이터 manses.sql 로 만든 표. JVM 에서 처음 부를 때 한 번만 읽고, 그 뒤로는 같은 객체를 돌려준다.
	 */
	public static ManseTableFixture realTable() {
		return RealTableHolder.TABLE;
	}

	/**
	 * 손으로 만든 행으로 만든 표. 절입 경계 몇 개만으로 월운 같은 계산을 확인할 때 쓴다.
	 */
	public static ManseTableFixture of(Manse... rows) {
		return new ManseTableFixture(Arrays.asList(rows));
	}

	/**
	 * 표의 모든 행. 실데이터는 덤프에 적힌 순서(양력 날짜 순)다. 바꿀 수 없는 목록이다.
	 */
	public List<Manse> rows() {
		return rows;
	}

	/**
	 * 만세력 계산이 쓰는 조회 메서드 다섯 개가 이 표로 답하는 새 목 저장소. 부를 때마다 새 목을 만들어 테스트끼리 호출 기록이
	 * 섞이지 않는다. 조회 횟수를 세는 테스트는 이 목의 호출 기록을 본다.
	 *
	 * <p>이 목은 쓰지 않는 스텁을 실패로 보는 검사(strict stubs)를 끈다. 표 전체를 흉내 내는 스텁이라 테스트마다 부르는 조회가
	 * 다르기 때문이다(양력으로 입력하면 음력 조회를 부르지 않는다). 인자가 틀리면 표가 다른 행을 돌려주므로 계산 결과 단언에서
	 * 드러난다.
	 *
	 * <p>다섯 개 밖의 메서드(findById, findAll 등)를 부르면 Mockito 기본값(빈 Optional, 빈 목록, null)을 조용히 돌려주지 않고,
	 * 메서드 이름을 담은 UnsupportedOperationException 을 던진다. 서비스가 다른 조회를 쓰게 되면 "데이터를 찾을 수 없습니다" 같은
	 * 엉뚱한 실패 대신 이 목에 그 조회의 답이 없다는 사실이 드러난다. 기본 답이 예외라서 when(목.조회(...)) 대신
	 * doAnswer(...).when(목).조회(...) 로 스텁한다.
	 */
	public ManseRepository newRepository() {
		ManseRepository repository = Mockito.mock(ManseRepository.class,
			withSettings().strictness(Strictness.LENIENT).defaultAnswer(ManseTableFixture::rejectUnmodeledCall));
		doAnswer(call -> Optional.ofNullable(bySolarDate.get(call.<LocalDate>getArgument(0))))
			.when(repository).findBySolarDate(any());
		doAnswer(call -> byLunarDate.getOrDefault(call.<LocalDate>getArgument(0), Collections.emptyList()))
			.when(repository).findAllByLunarDateOrderBySolarDateAsc(any());
		doAnswer(call -> valueOf(bySeasonStartTime.higherEntry(call.getArgument(0))))
			.when(repository).findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(any());
		doAnswer(call -> valueOf(bySeasonStartTime.floorEntry(call.getArgument(0))))
			.when(repository).findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(any());
		doAnswer(call -> bySeasonStartTime.tailMap(call.getArgument(0), true).values().stream()
			.limit(MONTHLY_SEASON_LIMIT)
			.toList())
			.when(repository).findTop13BySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(any());
		return repository;
	}

	/**
	 * 흉내 내지 않는 조회를 막는 기본 답. toString 같은 Object 메서드는 Mockito 기본값을 그대로 써서, 디버거나 오류 메시지가 목을
	 * 글자로 바꿀 때 실패하지 않게 한다.
	 */
	private static Object rejectUnmodeledCall(InvocationOnMock call) throws Throwable {
		if (call.getMethod().getDeclaringClass() == Object.class) {
			return Answers.RETURNS_DEFAULTS.answer(call);
		}
		throw new UnsupportedOperationException("ManseTableFixture 의 목 저장소는 " + call.getMethod().getName()
			+ " 를 흉내 내지 않는다. 서비스가 이 조회를 쓰게 됐다면 newRepository() 에 답을 더한다");
	}

	private static Optional<Manse> valueOf(Map.Entry<LocalDateTime, Manse> entry) {
		return Optional.ofNullable(entry).map(Map.Entry::getValue);
	}

	/** Manse 의 protected 생성자를 리플렉션으로 부른다. 이 우회는 이 클래스에만 둔다. */
	private static Manse newManse() {
		return BeanUtils.instantiateClass(Manse.class);
	}

	/** Manse 에는 setter 가 없어 필드에 바로 넣는다. 필드 이름이 바뀌면 ReflectionTestUtils 가 IllegalArgumentException 을 던진다. */
	private static void put(Manse manse, String fieldName, Object value) {
		ReflectionTestUtils.setField(manse, fieldName, value);
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
	 * 글자로 본다. 따옴표 안의 백슬래시는 다음 글자를 그대로 쓴다(\n 을 줄바꿈으로 풀지 않는다. 이 덤프에는 백슬래시가 없다).
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
		Manse manse = newManse();
		put(manse, "id", Long.valueOf(values.get(0)));
		put(manse, "solarDate", LocalDate.parse(values.get(1)));
		put(manse, "lunarDate", toDate(values.get(2)));
		put(manse, "season", values.get(3));
		put(manse, "seasonStartTime", toDateTime(values.get(4)));
		put(manse, "leapMonth", values.get(5) == null ? null : "1".equals(values.get(5)));
		put(manse, "yearSky", values.get(6));
		put(manse, "yearGround", values.get(7));
		put(manse, "monthSky", values.get(8));
		put(manse, "monthGround", values.get(9));
		put(manse, "daySky", values.get(10));
		put(manse, "dayGround", values.get(11));
		put(manse, "createdAt", toDateTime(values.get(12)));
		put(manse, "updatedAt", toDateTime(values.get(13)));
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

	/**
	 * 손으로 만든 만세력 한 행을 만든다. 테스트는 결과를 좌우하는 기둥과 절입만 바꾸고 나머지는 기본값을 쓴다.
	 *
	 * <p>기본값은 연주 甲子, 월주 丙子, 일주 甲子, 절입 없음, 윤달 아님이고, 음력 날짜는 양력 날짜와 같게 둔다. 호출할 때마다 새
	 * 빌더를 돌려주므로 테스트끼리 값이 섞이지 않는다. 쓰는 쪽은 아래처럼 쓴다.
	 * <pre>
	 * ManseRow.on(LocalDate.of(2026, 9, 25)).monthPillar("丙", "寅")
	 * 	.season("입춘", LocalDateTime.of(2026, 9, 25, 12, 0)).build()
	 * </pre>
	 */
	public static final class ManseRow {

		private final LocalDate solarDate;
		private String yearSky = "甲";
		private String yearGround = "子";
		private String monthSky = "丙";
		private String monthGround = "子";
		private String daySky = "甲";
		private String dayGround = "子";
		private String season;
		private LocalDateTime seasonStartTime;

		private ManseRow(LocalDate solarDate) {
			this.solarDate = solarDate;
		}

		/** 양력 날짜가 solarDate 인 행. */
		public static ManseRow on(LocalDate solarDate) {
			return new ManseRow(solarDate);
		}

		public ManseRow yearPillar(String sky, String ground) {
			this.yearSky = sky;
			this.yearGround = ground;
			return this;
		}

		public ManseRow monthPillar(String sky, String ground) {
			this.monthSky = sky;
			this.monthGround = ground;
			return this;
		}

		public ManseRow dayPillar(String sky, String ground) {
			this.daySky = sky;
			this.dayGround = ground;
			return this;
		}

		/** 이 날이 절입일이면 절기 이름과 절입 시각을 둔다. */
		public ManseRow season(String name, LocalDateTime startTime) {
			this.season = name;
			this.seasonStartTime = startTime;
			return this;
		}

		public Manse build() {
			Manse manse = newManse();
			put(manse, "solarDate", solarDate);
			put(manse, "lunarDate", solarDate);
			put(manse, "leapMonth", false);
			put(manse, "yearSky", yearSky);
			put(manse, "yearGround", yearGround);
			put(manse, "monthSky", monthSky);
			put(manse, "monthGround", monthGround);
			put(manse, "daySky", daySky);
			put(manse, "dayGround", dayGround);
			put(manse, "season", season);
			put(manse, "seasonStartTime", seasonStartTime);
			return manse;
		}
	}
}
