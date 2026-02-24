package com.mansereok.server.domain.user.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Table(name = "users")
@Entity
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private String username; // 사용자 아이디
	private String name; // 사용자 본명
	private String password;
	private String email;
	@Enumerated(EnumType.STRING)
	private Role role = Role.USER;
	private boolean enabled = true; // 계정 활성화 상태 .
	// Oauth
	@Enumerated(EnumType.STRING)
	private SocialType socialType;
	private String socialId;

	private boolean marketingAgreed = false;

	// profile 정보
	private LocalDate birthDate; // 생년월일 필드 추가
	private LocalTime birthTime; // 태어난 시각
	private String birthPlace; // 태어난 장소

	@Enumerated(EnumType.STRING)
	private Gender gender; // 성별 필드 추가

	// 개인정보처리방침 동의
	private boolean privacyPolicyAgreed = false;

	private LocalDateTime createdAt;
	private LocalDateTime updatedAt;

	public static User create(String username, String name, String password, String email,
		LocalDate birthDate, Gender gender, boolean enabled, boolean privacyPolicyAgreed,
		boolean marketingAgreed) {
		User user = new User();
		user.username = username;
		user.name = name;
		user.password = password;
		user.email = email; // 이메일 정보 강제 ..해야함
		user.birthDate = birthDate;
		user.gender = gender;
		user.enabled = enabled;
		user.privacyPolicyAgreed = privacyPolicyAgreed;
		user.marketingAgreed = marketingAgreed;

		user.createdAt = LocalDateTime.now();
		user.updatedAt = LocalDateTime.now();
		return user;
	}

	public static User createByOauth(String username, String name, String email, String socialId,
		SocialType socialType) {
		User user = new User();
		user.username = username; // 아이디
		user.name = name; // 이름
		user.email = email;
		user.createdAt = LocalDateTime.now();
		user.updatedAt = LocalDateTime.now();
		user.socialId = socialId;
		user.socialType = socialType;
		user.marketingAgreed = false;
		return user;
	}

	@PreUpdate // 엔티티 업데이트 될때마다 자동으로 updateAt 필드를 현재시간으로 설정 .
	protected void onUpdate() {
		this.updatedAt = LocalDateTime.now();
	}
}
