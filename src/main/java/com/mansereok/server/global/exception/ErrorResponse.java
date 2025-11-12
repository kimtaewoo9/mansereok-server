package com.mansereok.server.global.exception;

import java.time.LocalDateTime;
import java.util.Map;
import lombok.Getter;

@Getter
public class ErrorResponse {

	private final int status;
	private final String errorCode;
	private final String message;
	private final LocalDateTime timestamp;
	private final Map<String, String> errors; // validation 에러용

	private ErrorResponse(int status, String errorCode, String message,
		Map<String, String> errors) {
		this.status = status;
		this.errorCode = errorCode;
		this.message = message;
		this.timestamp = LocalDateTime.now();
		this.errors = errors;
	}

	public static ErrorResponse of(int status, String errorCode, String message) {
		return new ErrorResponse(status, errorCode, message, null);
	}

	public static ErrorResponse of(int status, String errorCode, String message,
		Map<String, String> errors) {
		return new ErrorResponse(status, errorCode, message, errors);
	}
}
