package com.mansereok.server.domain.interpret.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Table(name = "manses")
@Entity
@Getter
@Setter
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
