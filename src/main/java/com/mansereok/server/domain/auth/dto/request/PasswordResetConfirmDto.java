package com.mansereok.server.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 메일로 받은 재설정 토큰과 새 비밀번호. 새 비밀번호는 가입과 같은 {@link PasswordRule} 을 따른다.
 */
public record PasswordResetConfirmDto(
	@NotBlank(message = "재설정 토큰은 필수입니다")
	String token,

	@NotBlank(message = PasswordRule.REQUIRED_MESSAGE)
	@Size(min = PasswordRule.MIN_LENGTH, message = PasswordRule.TOO_SHORT_MESSAGE)
	String newPassword
) {

}
