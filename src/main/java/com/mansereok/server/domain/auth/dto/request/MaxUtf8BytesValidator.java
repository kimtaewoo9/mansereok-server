package com.mansereok.server.domain.auth.dto.request;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;

/**
 * {@link MaxUtf8Bytes} 를 검사한다. BCrypt 가 비밀번호를 UTF-8 바이트로 바꿔 한도를 재므로 같은 방식으로 센다.
 */
public class MaxUtf8BytesValidator implements ConstraintValidator<MaxUtf8Bytes, String> {

	private int maxBytes;

	@Override
	public void initialize(MaxUtf8Bytes constraint) {
		this.maxBytes = constraint.value();
	}

	@Override
	public boolean isValid(String value, ConstraintValidatorContext context) {
		if (value == null) {
			return true;
		}
		return value.getBytes(StandardCharsets.UTF_8).length <= maxBytes;
	}
}
