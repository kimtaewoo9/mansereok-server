package com.mansereok.server.support.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.mansereok.server.domain.interpret.entity.Manse;
import com.mansereok.server.domain.interpret.repository.ManseRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

/**
 * ManseTableFixture 가 manses.sql 을 DB 와 같은 값으로 읽고, 목 저장소가 조회 메서드 이름대로 답하는지 확인한다.
 *
 * <p>만세력 계산의 대표 사주 표는 이 도우미가 내는 값을 믿고 고정한다. 덤프를 잘못 읽거나 같은 시각 포함·제외를 뒤바꾸면 그 표
 * 전체가 틀린 값을 고정하게 되므로, 알려진 행 몇 개로 먼저 확인한다. 실제 MySQL 과 같은 답을 내는지는
 * ManseTableFixtureMatchesMySqlTest 가 본다.
 */
class ManseTableFixtureTest {

	private final ManseTableFixture realTable = ManseTableFixture.realTable();

	@Nested
	@DisplayName("manses.sql 을 읽으면")
	class WhenReadingDump {

		@Test
		@DisplayName("양력 1900-01-01 부터 2100-12-31 까지 73,414행을 날짜 순서대로 읽는다")
		void readsEveryRowInSolarDateOrder() {
			// when
			List<Manse> rows = realTable.rows();

			// then
			assertThat(rows).hasSize(73_414);
			assertThat(rows).isSortedAccordingTo(Comparator.comparing(Manse::getSolarDate));
			assertThat(rows.get(0).getSolarDate()).isEqualTo(LocalDate.of(1900, 1, 1));
			assertThat(rows.get(73_413).getSolarDate()).isEqualTo(LocalDate.of(2100, 12, 31));
		}

		@Test
		@DisplayName("음력 날짜가 0000-00-00 인 269행은 JDBC 처럼 음력 날짜를 null 로 읽는다")
		void readsZeroLunarDateAsNull() {
			// then
			assertThat(realTable.rows()).filteredOn(row -> row.getLunarDate() == null).hasSize(269);
			assertThat(realTable.newRepository().findBySolarDate(LocalDate.of(1900, 3, 29)))
				.hasValueSatisfying(row -> assertThat(row.getLunarDate()).isNull());
		}

		@Test
		@DisplayName("1993-06-06 은 망종 절입일이고 절입 시각 01:12 와 네 기둥 값이 덤프와 같다")
		void readsSeasonStartDayAsInDump() {
			// when
			Manse row = realTable.newRepository().findBySolarDate(LocalDate.of(1993, 6, 6)).orElseThrow();

			// then
			assertThat(row)
				.extracting(Manse::getId, Manse::getLunarDate, Manse::getSeason, Manse::getSeasonStartTime,
					Manse::getLeapMonth, Manse::getYearSky, Manse::getYearGround, Manse::getMonthSky,
					Manse::getMonthGround, Manse::getDaySky, Manse::getDayGround)
				.containsExactly(34_125L, LocalDate.of(1993, 4, 17), "망종", LocalDateTime.of(1993, 6, 6, 1, 12),
					false, "癸", "酉", "戊", "午", "戊", "午");
		}

		@Test
		@DisplayName("덤프는 JVM 에서 한 번만 읽고 같은 표를 돌려준다")
		void readsDumpOnlyOnce() {
			// when
			ManseTableFixture again = ManseTableFixture.realTable();

			// then
			assertThat(again).isSameAs(realTable);
		}
	}

	@Nested
	@DisplayName("목 저장소는")
	class WhenLookingUp {

		private final ManseRepository repository = realTable.newRepository();

		@Test
		@DisplayName("절입 조회에서 같은 시각을 넣는지 빼는지를 메서드 이름대로 가른다(망종 1993-06-06 01:12)")
		void separatesInclusiveAndExclusiveSeasonLookups() {
			// given
			LocalDateTime mangjong = LocalDateTime.of(1993, 6, 6, 1, 12);

			// then
			// AssertJ 의 Optional 단언에서 map 은 설명(as)을 버린 새 단언을 만든다. 그래서 map 은 단언 밖에서 하고 as 를 그 뒤에 둔다.
			assertThat(repository.findBySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(mangjong, Limit.of(13)))
				.as("같은 시각 포함(이후, 13개)").first().extracting(Manse::getSeason).isEqualTo("망종");
			assertThat(repository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(mangjong)
				.map(Manse::getSeason)).as("같은 시각 제외(이후)").hasValue("소서");
			assertThat(repository.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(mangjong)
				.map(Manse::getSeason)).as("같은 시각 포함(이전)").hasValue("망종");
			assertThat(repository.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(
				mangjong.minusSeconds(1)).map(Manse::getSeason)).as("1초 전(이전)").hasValue("입하");
		}

		@Test
		@DisplayName("표의 첫 절입보다 이전이나 마지막 절입보다 이후를 찾으면 비어 있다")
		void findsNothingOutsideSeasonRange() {
			// then
			assertThat(repository.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(
				LocalDateTime.of(1900, 1, 6, 4, 7, 59))).isEmpty();
			assertThat(repository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(
				LocalDateTime.of(2100, 12, 7, 10, 4))).isEmpty();
			assertThat(repository.findBySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(
				LocalDateTime.of(2100, 12, 7, 10, 4, 1), Limit.of(13))).isEmpty();
		}

		@Test
		@DisplayName("Limit 13 으로 조회하면 같은 시각을 넣어 절입 시각 순서로 13개까지 준다(망종 1993-06-06 01:12 부터 망종 1994-06-06 07:01 까지)")
		void findsThirteenSeasonsInOrder() {
			// when
			List<Manse> seasons = repository.findBySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(
				LocalDateTime.of(1993, 6, 6, 1, 12), Limit.of(13));

			// then
			assertThat(seasons).hasSize(13)
				.isSortedAccordingTo(Comparator.comparing(Manse::getSeasonStartTime));
			assertThat(seasons).first().extracting(Manse::getSeason, Manse::getSeasonStartTime)
				.containsExactly("망종", LocalDateTime.of(1993, 6, 6, 1, 12));
			assertThat(seasons).last().extracting(Manse::getSeason, Manse::getSeasonStartTime)
				.containsExactly("망종", LocalDateTime.of(1994, 6, 6, 7, 1));
		}

		@Test
		@DisplayName("절입 조회는 Limit 이 정한 개수만큼만 준다(망종 1993-06-06 01:12 부터 Limit 2)")
		void findsOnlyAsManySeasonsAsLimit() {
			// when
			List<Manse> seasons = repository.findBySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(
				LocalDateTime.of(1993, 6, 6, 1, 12), Limit.of(2));

			// then
			assertThat(seasons).extracting(Manse::getSeason).containsExactly("망종", "소서");
		}

		@Test
		@DisplayName("Limit 13 으로 조회해도 표 끝(2100-12-07 대설)에 가까우면 남은 절입만 준다(소한 2100-01-05 15:28 부터 12개)")
		void findsOnlyRemainingSeasonsNearEndOfTable() {
			// when
			List<Manse> seasons = repository.findBySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(
				LocalDateTime.of(2100, 1, 5, 15, 28), Limit.of(13));

			// then
			assertThat(seasons).extracting(Manse::getSeason).containsExactly(
				"소한", "입춘", "경칩", "청명", "입하", "망종", "소서", "입추", "백로", "한로", "입동", "대설");
		}

		@Test
		@DisplayName("같은 음력 날짜가 윤달과 평달 두 행이면 양력 날짜 순으로 둘 다 준다(음력 2020-04-15)")
		void givesLeapAndNormalMonthRowsByLunarDate() {
			// when
			List<Manse> candidates = repository.findAllByLunarDateOrderBySolarDateAsc(LocalDate.of(2020, 4, 15));

			// then
			assertThat(candidates)
				.extracting(Manse::getSolarDate, Manse::getLeapMonth)
				.containsExactly(
					tuple(LocalDate.of(2020, 5, 7), false),
					tuple(LocalDate.of(2020, 6, 6), true));
		}

		@Test
		@DisplayName("흉내 내지 않는 조회를 부르면 빈 값 대신 메서드 이름을 담은 UnsupportedOperationException 을 던진다")
		void rejectsLookupItDoesNotModel() {
			// when & then
			assertThatThrownBy(() -> repository.findById(34_125L))
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessage("ManseTableFixture 의 목 저장소는 findById 를 흉내 내지 않는다. "
					+ "서비스가 이 조회를 쓰게 됐다면 newRepository() 에 답을 더한다");
		}
	}
}
