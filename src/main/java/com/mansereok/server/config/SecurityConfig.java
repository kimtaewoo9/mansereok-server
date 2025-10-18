package com.mansereok.server.config;

import com.mansereok.server.filter.JwtAuthenticationFilter;
import com.mansereok.server.security.JwtAccessDeniedHandler;
import com.mansereok.server.security.JwtAuthenticationEntryPoint;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
	private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
//			 CSRF 설정

			// 일단 모두 허용 .
//			.csrf(csrf -> csrf.disable())

			.csrf(csrf -> csrf
				.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
				.ignoringRequestMatchers("/api/payment/webhook")
			)

			// CORS 설정 적용
			.cors(cors -> cors.configurationSource(corsConfigurationSource()))

			// 세션 관리 정책 설정 (IF_REQUIRED -> 필요할때만 생성)
			.sessionManagement(session ->
				session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

			// 인증이 필요한 경로 설정
			.authorizeHttpRequests(auth -> auth
					// 특정 경로를 먼저 허용
					.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
					.requestMatchers("/api/auth/**").permitAll()
					.requestMatchers("/api/payment/webhook").permitAll()
					// 프로필 관련 API는 인증된 사용자만 접근하도록 추가
					.requestMatchers("/api/v1/users/me/profiles").authenticated()
					.requestMatchers("/api/payments/me").authenticated()
					// 일단 모든 요청을 인증 없이 허용
					.anyRequest().permitAll()

				// .requestMatchers("/api/admin/**").hasRole("ADMIN")
				// .requestMatchers("/api/manager/**").hasAnyRole("ADMIN", "MANAGER")
				// .requestMatchers("/api/user/**").hasAnyRole("ADMIN", "MANAGER", "USER")
			)

			// JWT 인증 예외 처리
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint(jwtAuthenticationEntryPoint)
				.accessDeniedHandler(jwtAccessDeniedHandler))

			// H2 콘솔을 위한 헤더 설정
			.headers(headers -> headers
				.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable))

			// JWT 인증 필터 추가
			.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();

		// 허용할 도메인 설정
		configuration.setAllowedOrigins(
			Arrays.asList(
				"http://localhost:3000",
				"https://namedsaju.com",
				"https://www.namedsaju.com"
			)
		);

		// 허용할 HTTP 메서드
		configuration.setAllowedMethods(
			Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
		// 허용할 헤더
		configuration.setAllowedHeaders(Arrays.asList("*"));
		// 자격증명 허용 (쿠키, Authorization 헤더 등)
		configuration.setAllowCredentials(true);
		// 브라우저에서 접근할 수 있는 응답 헤더
		configuration.setExposedHeaders(Arrays.asList("Authorization"));
		// preflight 요청 캐시 시간 (초)
		configuration.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);

		return source;
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public AuthenticationManager authenticationManager(
		AuthenticationConfiguration authConfig) throws Exception {
		return authConfig.getAuthenticationManager();
	}
}
