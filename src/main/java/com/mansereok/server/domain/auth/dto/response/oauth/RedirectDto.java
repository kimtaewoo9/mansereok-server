package com.mansereok.server.domain.auth.dto.response.oauth;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RedirectDto {

	private String code;
}
