package com.mansereok.server.service.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PresignedUrlResponse {

	private String presignedUrl;
	private String objectKey;
	private int expiresIn;
}
