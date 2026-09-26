package com.mansereok.server.domain.interpret.repository;

import com.mansereok.server.domain.interpret.entity.Manse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * manses 조회. 절입 시각으로 찾는 조회는 idx_manses_season_start_time, 음력 날짜로 찾는 조회는
 * idx_manses_lunar_date_leap_month 를 쓴다(Manse 의 @Table 선언).
 */
@Repository
public interface ManseRepository extends JpaRepository<Manse, Long> {

	/**
	 * 양력 날짜로 만세력 조회
	 */
	Optional<Manse> findBySolarDate(LocalDate solarDate);

	/**
	 * 음력 날짜로 만세력 전체 조회. 윤달이 낀 날짜는 평달과 윤달 두 행이 양력 날짜 순서로 온다. 윤달 여부는 이 목록에서 고른다.
	 */
	List<Manse> findAllByLunarDateOrderBySolarDateAsc(LocalDate lunarDate);

	/**
	 * 절입시간이 특정 시간 이후인 첫 번째 만세력 조회 (대운 순행용 - 같은 시간 제외)
	 */
	Optional<Manse> findFirstBySeasonStartTimeGreaterThanOrderBySeasonStartTimeAsc(
		LocalDateTime datetime);

	/**
	 * 절입시간이 특정 시간 이전인 첫 번째 만세력 조회 (대운 역행용, 월운의 현재 절입 기준점 - 같은 시간 포함)
	 */
	Optional<Manse> findFirstBySeasonStartTimeLessThanEqualOrderBySeasonStartTimeDesc(
		LocalDateTime datetime);

	/**
	 * 절입시간이 특정 시간과 같거나 이후인 절입일을 절입 시각 순서로 limit 개까지 조회 (월운용 - 같은 시간 포함). 월운은 달 수보다
	 * 하나 많게(마지막 달이 끝나는 다음 절입까지) 읽는다. 표 끝(2100년)에 가까우면 남은 만큼만 온다.
	 */
	List<Manse> findBySeasonStartTimeGreaterThanEqualOrderBySeasonStartTimeAsc(LocalDateTime datetime, Limit limit);
}
