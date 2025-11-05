package com.mansereok.server.domain.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AccessTokenDto {

	private String access_token;
	private String expires_in;
	private String scope;
	private String token_type; // "Bearer"
	private String id_token; //
}
