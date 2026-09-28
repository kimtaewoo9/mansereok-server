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
import org.hibernate.annotations.DynamicUpdate;

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
// 바뀐 컬럼만 UPDATE 한다. 예전처럼 모든 컬럼을 UPDATE 하면, 사용자 행을 읽고 이름만 바꾼 프로필 수정이 커밋할 때 그사이 커밋된
// 비밀번호 재설정을 읽어 둔 옛 비밀번호 해시로 덮어 되돌린다. 이제 서로 다른 컬럼을 바꾸는 두 수정은 서로를 덮지 않는다. 같은 컬럼을
// 동시에 바꾸면 여전히 나중에 커밋한 쪽이 남는다.
@DynamicUpdate
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	/**
	 * 로그인 아이디. JWT 의 subject 이고, 로그인한 사용자를 이 값으로 찾는다. 이메일 가입자는 이메일, 소셜 가입자는 제공자의 사용자
	 * 번호(네이버는 무작위 10자리 대문자)다. 가입할 때 한 번 정하고 바꾸지 않는다. 화면에 보이는 이름은 {@link #name} 이다.
	 */
	private String username;
	private String name; // 사용자 본명
	// 비밀번호는 changePassword 로만 바꾼다.
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

	/**
	 * 비밀번호를 바꾼다. 비밀번호를 바꾸는 길은 이 메서드 하나다.
	 *
	 * @param encodedPassword 암호화(BCrypt)를 마친 새 비밀번호. 평문을 넘기지 않는다.
	 */
	public void changePassword(String encodedPassword) {
		this.password = encodedPassword;
	}

	/**
	 * 프로필을 바꾼다. change 에서 null 인 항목은 그대로 두고, 이름이 공백만 있으면 이름을 바꾸지 않는다.
	 *
	 * <p>바뀐 뒤의 값을 먼저 모두 계산해 검사하고, 통과했을 때만 필드에 넣는다. 그래서 예외가 나면 어떤 필드도 바뀌지 않는다. 트랜잭션
	 * 안에서 필드를 먼저 바꾸고 나중에 거절하면, 예외를 잡아 삼키는 호출자가 있을 때 절반만 바뀐 프로필이 변경 감지로 저장된다.
	 *
	 * <p>필수: 이름, 생년월일, 성별. 선택: 태어난 시각, 태어난 장소. 마케팅 동의 여부와 관계없이 같은 규칙이다.
	 *
	 * <p>이름 길이({@link NameRule#MAX_LENGTH})는 이름을 지금과 다른 값으로 바꿀 때만 검사한다. 소셜 가입자의 이름은 제공자가 준
	 * 값이라 20자를 넘을 수 있는데, 추가 정보 입력 화면이 그 이름을 그대로 담아 보내도 생년월일·성별을 저장할 수 있어야 한다.
	 *
	 * @throws IllegalArgumentException 태어난 장소가 공백만 있거나, 바뀐 뒤 이름·생년월일·성별 중 하나라도 비어 있거나, 이름을
	 *                                  20자가 넘는 다른 이름으로 바꾸려 할 때(400)
	 */
	public void updateProfile(ProfileChange change) {
		String newName = hasText(change.name()) ? change.name() : this.name;
		LocalDate newBirthDate = change.birthDate() != null ? change.birthDate() : this.birthDate;
		LocalTime newBirthTime = change.birthTime() != null ? change.birthTime() : this.birthTime;
		String newBirthPlace = this.birthPlace;
		if (change.birthPlace() != null) {
			if (change.birthPlace().isBlank()) {
				throw new IllegalArgumentException("태어난 장소는 공백일 수 없습니다.");
			}
			newBirthPlace = change.birthPlace().trim();
		}
		Gender newGender = change.gender() != null ? change.gender() : this.gender;
		boolean newMarketingAgreed =
			change.marketingAgreed() != null ? change.marketingAgreed() : this.marketingAgreed;

		if (!hasText(newName)) {
			throw new IllegalArgumentException("이름을 입력해주세요.");
		}
		if (!newName.equals(this.name) && newName.length() > NameRule.MAX_LENGTH) {
			throw new IllegalArgumentException(NameRule.TOO_LONG_MESSAGE);
		}
		if (newBirthDate == null) {
			throw new IllegalArgumentException("생년월일을 입력해주세요.");
		}
		if (newGender == null) {
			throw new IllegalArgumentException("성별을 선택해주세요.");
		}

		this.name = newName;
		this.birthDate = newBirthDate;
		this.birthTime = newBirthTime;
		this.birthPlace = newBirthPlace;
		this.gender = newGender;
		this.marketingAgreed = newMarketingAgreed;
	}

	/**
	 * 사주 해석에 꼭 필요한 프로필(이름, 생년월일, 성별) 중 하나라도 비어 있는가. 소셜 가입자는 가입 직후 이 값들이 비어 있어,
	 * 프론트가 이 값을 보고 추가 정보 입력 화면을 띄운다.
	 */
	public boolean isProfileIncomplete() {
		return !hasText(name) || birthDate == null || gender == null;
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	@PreUpdate // 엔티티 업데이트 될때마다 자동으로 updateAt 필드를 현재시간으로 설정 .
	protected void onUpdate() {
		this.updatedAt = LocalDateTime.now();
	}
}
