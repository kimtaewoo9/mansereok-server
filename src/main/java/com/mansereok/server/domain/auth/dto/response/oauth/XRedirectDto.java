package com.mansereok.server.domain.auth.dto.response.oauth;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class XRedirectDto {

	private String code;
	private String codeVerifier;
}
