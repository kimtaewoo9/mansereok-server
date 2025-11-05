package com.mansereok.server.domain.interpret.repository;

import com.mansereok.server.domain.interpret.entity.Manse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ManseRepository extends JpaRepository<Manse, Long> {

	/**
	 * 양력 날짜로 만세력 조회
	 */
	Optional<Manse> findBySolarDate(LocalDate solarDate);

	/**
	 * 음력 날짜로 만세력 조회
	 */
	Optional<Manse> findByLunarDate(LocalDate lunarDate);

	/**
	 * 절입시간이 특정 시간 이후인 첫 번째 만세력 조회 (순행용)
	 */
	@Query("SELECT m FROM Manse m WHERE m.seasonStartTime >= :datetime AND m.seasonStartTime IS NOT NULL ORDER BY m.solarDate ASC LIMIT 1")
	Optional<Manse> findFirstBySeasonStartTimeGreaterThanEqualOrderBySolarDateAsc(
		@Param("datetime") LocalDateTime datetime);

	/**
	 * 절입시간이 특정 시간 이전인 첫 번째 만세력 조회 (역행용)
	 */
	@Query("SELECT m FROM Manse m WHERE m.seasonStartTime <= :datetime AND m.seasonStartTime IS NOT NULL ORDER BY m.solarDate DESC LIMIT 1")
	Optional<Manse> findFirstBySeasonStartTimeLessThanEqualOrderBySolarDateDesc(
		@Param("datetime") LocalDateTime datetime);

	/**
	 * 절기 정보가 있는 만세력들 조회
	 */
	@Query("SELECT m FROM Manse m WHERE m.season IS NOT NULL AND m.seasonStartTime IS NOT NULL ORDER BY m.solarDate")
	List<Manse> findAllBySeason();
}
