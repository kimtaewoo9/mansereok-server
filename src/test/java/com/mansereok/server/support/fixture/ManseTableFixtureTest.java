package com.mansereok.server.support.fixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.mansereok.server.domain.interpret.entity.Manse;
import com.mansereok.server.domain.interpret.repository.ManseRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ManseTableFixture 가 manses.sql 을 DB 와 같은 값으로 읽고, 목 저장소가 조회 메서드 이름대로 답하는지 확인한다.
 *
 * <p>만세력 계산의 대표 사주 표는 이 도우미가 내는 값을 믿고 고정한다. 덤프를 잘못 읽거나 같은 시각 포함·제외를 뒤바꾸면 그 표
 * 전체가 틀린 값을 고정하게 되므로, 알려진 행 몇 개로 먼저 확인한다. 실제 MySQL 과 같은 답을 내는지는 ManseBaselineMySqlTest 가
 * 본다.
 */
class ManseTableFixtureTest {

	private final ManseTableFixture realTable = ManseTableFixture.realTable();

	@Nested
	@DisplayName("manses.sql 을 읽으면")
	class WhenReadingDump {

		@Test
		@DisplayName("양력 1900-01-01 부터 2100-12-31 까지 73,414행을 날짜 순서대로 읽는다")
		void readsEveryRowInSolarDateOrder() {
			// then
			assertThat(realTable.rows()).hasSize(73_414);
			assertThat(realTable.rows().get(0).getSolarDate()).isEqualTo(LocalDate.of(1900, 1, 1));
			assertThat(realTable.rows().get(73_413).getSolarDate()).isEqualTo(LocalDate.of(2100, 12, 31));
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
			assertThat(repository.findFirstBySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(mangjong))
				.as("같은 시각 포함(이후)").map(Manse::getSeason).hasValue("망종");
			assertThat(repository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(mangjong))
				.as("같은 시각 제외(이후)").map(Manse::getSeason).hasValue("소서");
			assertThat(repository.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(mangjong))
				.as("같은 시각 포함(이전)").map(Manse::getSeason).hasValue("망종");
			assertThat(repository.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(
				mangjong.minusSeconds(1)))
				.as("1초 전(이전)").map(Manse::getSeason).hasValue("입하");
		}

		@Test
		@DisplayName("표의 첫 절입보다 이전이나 마지막 절입보다 이후를 찾으면 비어 있다")
		void findsNothingOutsideSeasonRange() {
			// then
			assertThat(repository.findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(
				LocalDateTime.of(1900, 1, 6, 4, 7, 59))).isEmpty();
			assertThat(repository.findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(
				LocalDateTime.of(2100, 12, 7, 10, 4))).isEmpty();
		}

		@Test
		@DisplayName("같은 음력 날짜가 윤달과 평달 두 행이면 양력 날짜 순으로 주고, 윤달 여부로 한 행을 고른다(음력 2020-04-15)")
		void picksLeapOrNormalMonthByLunarDate() {
			// given
			LocalDate lunarDate = LocalDate.of(2020, 4, 15);

			// then
			assertThat(repository.findAllByLunarDateOrderBySolarDateAsc(lunarDate))
				.extracting(Manse::getSolarDate, Manse::getLeapMonth)
				.containsExactly(
					tuple(LocalDate.of(2020, 5, 7), false),
					tuple(LocalDate.of(2020, 6, 6), true));
			assertThat(repository.findByLunarDateAndLeapMonth(lunarDate, true))
				.map(Manse::getSolarDate).hasValue(LocalDate.of(2020, 6, 6));
			assertThat(repository.findByLunarDateAndLeapMonth(lunarDate, false))
				.map(Manse::getSolarDate).hasValue(LocalDate.of(2020, 5, 7));
		}
	}
}
