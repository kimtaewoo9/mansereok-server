package com.mansereok.server.domain.user.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// 제약 이름을 고정해 엔티티, schema.sql, 운영 DB 가 같은 이름을 쓰게 한다. 운영은 ddl-auto: validate 라 UNIQUE 를 검사하지
// 않으므로, 바꿀 때는 운영 DDL 과 schema.sql 을 함께 고친다.
@Table(
	name = "users",
	uniqueConstraints = {
		// 가입 전 existsByEmail 확인만으로는 동시에 들어온 같은 이메일 가입을 막지 못한다. 마지막으로 DB 가 막는다.
		// 이메일이 없는(NULL) 소셜 계정은 여러 개여도 걸리지 않는다.
		@UniqueConstraint(name = "uk_users_email", columnNames = "email"),
		// 로그인한 사용자를 username 으로 찾는다(JWT subject). 두 행이 같은 username 이면 그 사용자의 모든 요청이 실패한다.
		@UniqueConstraint(name = "uk_users_username", columnNames = "username"),
		// 소셜 계정 하나에 회원 하나. 소셜 로그인의 첫 조회(findBySocialTypeAndSocialId)가 이 인덱스로 한 행만 본다.
		// social_id 를 앞에 두어 social_id 만으로 찾는 조회도 이 인덱스를 쓴다. 일반 가입자는 두 컬럼이 모두 NULL 이라 걸리지 않는다.
		@UniqueConstraint(name = "uk_users_social_id_type", columnNames = {"social_id", "social_type"})
	}
)
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
