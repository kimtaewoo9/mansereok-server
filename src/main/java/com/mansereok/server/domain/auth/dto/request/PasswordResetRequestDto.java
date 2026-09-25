package com.mansereok.server.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 비밀번호 재설정 메일을 받을 이메일. 가입 요청과 같은 규칙으로 검사한다.
 */
public record PasswordResetRequestDto(
	@NotBlank(message = "이메일은 필수입니다")
	@Email(message = "올바른 이메일 형식을 입력해주세요.")
	String email
) {

}
