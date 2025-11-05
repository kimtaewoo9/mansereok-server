package com.mansereok.server.domain.auth.dto.response.oauth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class NaverRedirectDto {

	private String code;
	private String state;
}
