package com.mansereok.server.domain.user.entity;

import lombok.Getter;

@Getter
public enum Role {
	USER("ROLE_USER"),
	ADMIN("ROLE_ADMIN"),
	MANAGER("ROLE_MANAGER"),
	SUPER_ADMIN("ROLE_SUPER_ADMIN");

	private final String authority;

	Role(String authority) {
		this.authority = authority;
	}
}
