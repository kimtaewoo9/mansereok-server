package com.mansereok.server.domain.interpret.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 만세력 기준 데이터 한 행(양력 하루). 1900~2100년 고정 참조 데이터라 애플리케이션은 읽기만 하므로 setter 를 두지 않는다.
// 운영은 ddl-auto: validate 라 인덱스를 검사하지도 만들지도 않는다. 인덱스를 바꿀 때는 운영 DDL 과 schema.sql 의 manses 인덱스
// 블록을 같은 이름으로 함께 고친다. solar_date 의 UNIQUE 는 schema.sql 의 CREATE TABLE 에 있다.
@Table(name = "manses", indexes = {
	// 월운과 대운이 절입 시각의 범위(>=, >, <=)로 찾고 그 순서로 정렬한다. 없으면 조회마다 표 전체(약 7.3만 행)를 읽고 정렬한다.
	@Index(name = "idx_manses_season_start_time", columnList = "season_start_time"),
	// 음력 입력은 음력 날짜로 평달·윤달 후보를 찾는다. 음력 날짜가 0000-00-00 인 행 269개가 서로 겹쳐 UNIQUE 로 걸 수 없다.
	@Index(name = "idx_manses_lunar_date_leap_month", columnList = "lunar_date, leap_month")
})
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Manse {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "solar_date", nullable = false)
	private LocalDate solarDate;

	@Column(name = "lunar_date", nullable = false)
	private LocalDate lunarDate;

	@Column(name = "season", length = 10)
	private String season;

	@Column(name = "season_start_time")
	private LocalDateTime seasonStartTime;

	@Column(name = "leap_month")
	private Boolean leapMonth;

	@Column(name = "year_sky", length = 10)
	private String yearSky;

	@Column(name = "year_ground", length = 10)
	private String yearGround;

	@Column(name = "month_sky", length = 10)
	private String monthSky;

	@Column(name = "month_ground", length = 10)
	private String monthGround;

	@Column(name = "day_sky", length = 10)
	private String daySky;

	@Column(name = "day_ground", length = 10)
	private String dayGround;

	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	@PrePersist
	protected void onCreate() {
		createdAt = LocalDateTime.now();
		updatedAt = LocalDateTime.now();
	}

	@PreUpdate
	protected void onUpdate() {
		updatedAt = LocalDateTime.now();
	}
}
