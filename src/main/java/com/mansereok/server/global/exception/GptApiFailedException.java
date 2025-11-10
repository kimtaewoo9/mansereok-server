package com.mansereok.server.global.exception;

public class GptApiFailedException extends RuntimeException {

	public GptApiFailedException(String message, Throwable cause) {
		super(message, cause);
	}
}
