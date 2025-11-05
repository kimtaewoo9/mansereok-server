package com.mansereok.server.global.config;

import com.mansereok.server.domain.auth.filter.JwtAuthenticationFilter;
import com.mansereok.server.domain.auth.security.JwtAccessDeniedHandler;
import com.mansereok.server.domain.auth.security.JwtAuthenticationEntryPoint;
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
				.ignoringRequestMatchers("/api/payment/webhook",
					"/member/**", // <-- 이 경로 추가
					"/api/auth/**"
				)
			)

			// CORS 설정 적용
			.cors(cors -> cors.configurationSource(corsConfigurationSource()))

			// 세션 관리 정책 설정 (IF_REQUIRED -> 필요할때만 생성)
			.sessionManagement(session ->
				session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

			.authorizeHttpRequests(auth -> auth
				// 1. 인증 없이 접근 허용 (permitAll)
				.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // CORS Preflight 요청
				// AuthController: 회원가입, 로그인, 토큰갱신, 로그아웃, CSRF 토큰 발급
				.requestMatchers("/api/auth/**").permitAll()
				// OauthController: 소셜 로그인 콜백 처리
				.requestMatchers("/member/**").permitAll()
				// PaymentController: 결제 웹훅 수신
				.requestMatchers("/api/payment/webhook").permitAll()
				// ProductController: 상품 목록 및 상세 정보 조회 (GET 요청만 허용)
				.requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/{productId}")
				.permitAll()
				// Swagger UI 접근 (개발/테스트 환경용)
				.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
				// Posteller 프록시 API (InterpretationController, PostellerController) - 일단 인증 없이 허용
				// 만약 이 API들도 인증이 필요하다면 아래 .authenticated() 섹션으로 이동
				.requestMatchers(
					"/api/v1/manseryeok/interpretation/{subcategoryId}/posteller",
					"/api/v1/manseryeok/compatibility/{subcategoryId}/posteller"
				).permitAll()
				.requestMatchers("/api/v1/manseryeok/daeun", "/api/v1/manseryeok/chart",
					"/api/v1/manseryeok/points").permitAll()
				.requestMatchers("/actuator/health").permitAll()

				// ProfileController: 내 정보 관련 모든 API
				.requestMatchers("/api/v1/users/me/**").authenticated()
				// PaymentController: 내 결제 내역, 특정 주문/결제 조회, 주문 생성, 결제 완료 확인
				.requestMatchers("/api/payments/me").authenticated()
				.requestMatchers("/api/orders/by-payment/{paymentId}").authenticated()
				.requestMatchers("/api/payments/{paymentId}/**")
				.authenticated() // Payment PK로 조회하는 API들
				.requestMatchers("/api/payment/orders/**").authenticated()
				.requestMatchers("/api/payment/complete").authenticated()
				// ManseryeokController: 만세력 계산, 사주/궁합 해석 요청
				.requestMatchers("/api/v1/manseryeok/calculate").authenticated()
				.requestMatchers("/api/v1/manseryeok/interpret/**").authenticated()

				// 3. 그 외 모든 요청은 인증 필요 (기본 규칙)
				.anyRequest().authenticated()
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
