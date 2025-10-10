package com.mansereok.server.service;

import com.mansereok.server.entity.User;
import com.mansereok.server.repository.UserRepository;
import java.util.Collection;
import java.util.Collections;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CustomUserDetailsService implements UserDetailsService {

	// Spring Security는 사용자를 인증할때 CustomerUserDetailsService를 사용해서 사용자 정보를 조회함.
	private final UserRepository userRepository;

	public CustomUserDetailsService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Override
	public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
		User user = userRepository.findByEmail(email)
			.orElseThrow(() -> new UsernameNotFoundException("사용자를 찾을 수 없습니다: " + email));

		return new CustomUserPrincipal(user);
	}

	/**
	 * Spring Security의 UserDetails 인터페이스를 구현한 커스텀 클래스 User 엔티티를 Spring Security가 이해할 수 있는 형태로
	 * 변환한다.
	 */
	public static class CustomUserPrincipal implements UserDetails {

		private final User user;

		public CustomUserPrincipal(User user) {
			this.user = user;
		}

		@Override
		public Collection<? extends GrantedAuthority> getAuthorities() {
			// 사용자의 역할을 Spring Security 권한으로 변환
			return Collections.singletonList(
				new SimpleGrantedAuthority(user.getRole().getAuthority()));
		}

		@Override
		public String getPassword() {
			return user.getPassword();
		}

		@Override
		public String getUsername() {
			return user.getUsername();
		}

		// 계정 만료 여부 (현재는 모든 계정이 만료되지 않음)
		@Override
		public boolean isAccountNonExpired() {
			return true;
		}

		// 계정 잠금 여부 (현재는 모든 계정이 잠기지 않음)
		@Override
		public boolean isAccountNonLocked() {
			return true;
		}

		// 자격 증명 만료 여부 (현재는 모든 자격 증명이 만료되지 않음)
		@Override
		public boolean isCredentialsNonExpired() {
			return true;
		}

		// 계정 활성화 여부
		@Override
		public boolean isEnabled() {
			return user.isEnabled();
		}

		// User 엔티티에 접근할 수 있는 메서드
		public User getUser() {
			return user;
		}
	}
}
