package com.mansereok.server.domain.auth.dto.response.oauth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NaverProfileDto {

	private String resultcode;
	private String message;
	private Response response;

	@Data
	public static class Response {

		private String id; // social id
		private String nickname;
		private String email;
		private String name;
	}
}
