package com.mansereok.server.global.exception;

public class PostellerApiException extends RuntimeException {

	public PostellerApiException(String message) {
		super(message);
	}

	public PostellerApiException(String message, Throwable cause) {
		super(message, cause);
	}
}
