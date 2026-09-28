package com.mansereok.server.domain.auth.dto.request;


import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.NameRule;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 이메일 회원가입 요청.
 *
 * <p>역할(role)은 받지 않는다. 가입한 회원은 언제나 USER 로 시작한다. 요청 본문에 role 이 있어도 읽을 필드가 없어 무시된다.
 */
@Data
@NoArgsConstructor
public class RegisterRequest {

	// 사용자 본명(User.name). 로그인 아이디(User.username)는 이메일이다.
	@NotBlank(message = NameRule.REQUIRED_MESSAGE)
	@Size(max = NameRule.MAX_LENGTH, message = NameRule.TOO_LONG_MESSAGE)
	private String name;

	@NotBlank(message = "이메일은 필수입니다")
	@Email(message = "올바른 이메일 형식을 입력해주세요.")
	private String email;

	@NotBlank(message = PasswordRule.REQUIRED_MESSAGE)
	@Size(min = PasswordRule.MIN_LENGTH, message = PasswordRule.TOO_SHORT_MESSAGE)
	@MaxUtf8Bytes(value = PasswordRule.MAX_BYTES, message = PasswordRule.TOO_LONG_MESSAGE)
	private String password;

	private LocalDate birthDate;

	private Gender gender;

	// 개인정보 처리방침 동의. 동의하지 않으면 가입할 수 없다.
	@AssertTrue(message = "개인정보 처리방침에 동의해야 가입할 수 있습니다.")
	private boolean privacyPolicyAgreed;

	private boolean marketingAgreed; // 마케팅 동의 항목
}
