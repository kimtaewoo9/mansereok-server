package com.mansereok.server.domain.auth.dto.request;

public record PasswordResetConfirmDto(
	String token,
	String newPassword
) {

}
