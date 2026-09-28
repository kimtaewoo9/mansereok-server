package com.mansereok.server.global.config;

import com.mansereok.server.domain.auth.filter.AllowedOriginFilter;
import com.mansereok.server.domain.auth.filter.JwtAuthenticationFilter;
import com.mansereok.server.domain.auth.security.JwtAccessDeniedHandler;
import com.mansereok.server.domain.auth.security.JwtAuthenticationEntryPoint;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@EnableConfigurationProperties({CorsProperties.class, ScrapeTokenProperties.class})
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
	private final JwtAccessDeniedHandler jwtAccessDeniedHandler;
	private final CorsProperties corsProperties;
	private final ScrapeTokenProperties scrapeTokenProperties;

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		CookieCsrfTokenRepository repo = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repo.setCookieCustomizer(c -> c
			.path("/")
			.sameSite("None")
			.secure(true)
		);

		http
			// CSRF: XSRF-TOKEN 쿠키로 내려준 토큰을 X-XSRF-TOKEN 헤더로 돌려받아야 쓰기 요청이 통과한다.
			// 아래 경로는 토큰을 확인하지 않는다. 그중 쿠키로 동작하는 재발급·로그아웃·소셜 로그인은 AllowedOriginFilter 가
			// 요청을 보낸 출처를 대신 확인한다.
			.csrf(csrf -> csrf
				.csrfTokenRepository(repo)
				.csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler())
				.ignoringRequestMatchers("/api/payment/webhook",
					"/member/**",
					"/api/auth/**",
					"/swagger-ui/**",
					"/v3/api-docs/**",
					"/actuator/**",
					"/api/v1/manseryeok/calculate"
				)
			)

			// CORS 설정 적용
			.cors(cors -> cors.configurationSource(corsConfigurationSource()))

			// 세션 관리 정책 설정 (IF_REQUIRED -> 필요할때만 생성)
			.sessionManagement(session ->
				session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))

			// 로그인 없이 부를 수 있는 경로와 역할이 필요한 경로는 여기 한 곳에서만 정한다.
			// JwtAuthenticationFilter 는 공개 여부를 따지지 않고(OPTIONS·actuator 요청만 건너뜀) 토큰이 있으면 해석만 한다.
			// 규칙은 SecurityRulesTest 의 표로 고정한다.
			.authorizeHttpRequests(auth -> auth
				// 1. 인증 없이 접근 허용 (permitAll)
				.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll() // CORS Preflight 요청
				.requestMatchers("/", "/error", "/favicon.ico").permitAll()
				// AuthController: 회원가입, 로그인, 토큰갱신, 로그아웃, CSRF 토큰 발급
				.requestMatchers("/api/auth/**").permitAll()
				// OauthController: 소셜 로그인 콜백 처리
				.requestMatchers("/member/**").permitAll()
				// PaymentController: 결제 웹훅 수신
				.requestMatchers("/api/payment/webhook").permitAll()
				// ProductController: 상품 목록 및 상세 정보 조회 (GET 요청만 허용)
				.requestMatchers(HttpMethod.GET, "/api/v1/products", "/api/v1/products/{productId}")
				.permitAll()
				// ReviewController: 리뷰 목록과 페이지 단위 목록 조회 (GET 요청만 허용)
				.requestMatchers(HttpMethod.GET, "/api/v1/reviews", "/api/v1/reviews/pagination")
				.permitAll()
				// ManseryeokController: 만세력 계산
				.requestMatchers("/api/v1/manseryeok/calculate").permitAll()
				// Swagger UI 접근 (개발/테스트 환경용)
				.requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
				// 서버 상태 확인(로드 밸런서·컨테이너 헬스 체크). 응답에는 UP·DOWN 같은 상태만 담는다(show-details: never).
				.requestMatchers("/actuator/health").permitAll()

				// 2. 로그인한 회원만 접근 허용 (여기 없는 경로도 5번 기본 규칙에 따라 로그인이 필요하다)
				// ProfileController: 내 정보 관련 모든 API
				.requestMatchers("/api/v1/users/me/**").authenticated()
				// PaymentController: 내 결제 내역, 특정 주문/결제 조회, 주문 생성, 결제 완료 확인
				.requestMatchers("/api/payments/me").authenticated()
				.requestMatchers("/api/orders/by-payment/{paymentId}").authenticated()
				.requestMatchers("/api/payments/{paymentId}/**")
				.authenticated() // Payment PK로 조회하는 API들
				.requestMatchers("/api/payment/orders/**").authenticated()
				.requestMatchers("/api/payment/complete").authenticated()
				// DiscountController: 할인 코드 확인
				.requestMatchers("/api/payment/discount").authenticated()
				// ManseryeokController: 사주/궁합 해석 요청
				.requestMatchers("/api/v1/manseryeok/interpret/**").authenticated()

				// 3. 관리자만 접근 허용
				// ReviewController: 리뷰 삭제 (ReviewService 도 요청자의 역할을 한 번 더 확인한다)
				.requestMatchers(HttpMethod.DELETE, "/api/v1/reviews/**")
				.hasAnyRole("ADMIN", "SUPER_ADMIN")

				// 4. health 를 뺀 actuator 경로(지표 수집 /actuator/prometheus 등)는 수집 토큰을 실은 요청만 받는다.
				// 회원 토큰으로는 열리지 않는다. 어떤 actuator 를 노출할지는 yml 의 management.endpoints.web.exposure 가 정한다.
				.requestMatchers("/actuator/**")
				.access(new ScrapeTokenAuthorizationManager(scrapeTokenProperties))

				// 5. 그 외 모든 요청은 인증 필요 (기본 규칙)
				.anyRequest().authenticated()
			)
			// JWT 인증 예외 처리
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint(jwtAuthenticationEntryPoint)
				.accessDeniedHandler(jwtAccessDeniedHandler))

			// 쿠키로 동작하는 재발급·로그아웃·소셜 로그인은 허용 출처가 아니면 CORS 처리 전에 막는다.
			.addFilterBefore(new AllowedOriginFilter(corsProperties.allowedOrigins()), CorsFilter.class)

			// JWT 인증 필터 추가
			.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	/**
	 * CSRF 토큰을 원문 그대로 주고받는 처리기. 프론트엔드가 XSRF-TOKEN 쿠키 값을 읽어 X-XSRF-TOKEN 헤더(또는 _csrf
	 * 파라미터)에 그대로 담아 보내면 통과한다.
	 *
	 * <p>스프링 시큐리티 기본 처리기(XorCsrfTokenRequestAttributeHandler)와 달리 토큰을 XOR 로 가려 내려주지도, 가려진 값을
	 * 풀지도 않는다. 그래서 XOR 로 가린 토큰을 보내면 쿠키 값과 달라 403 CSRF_FORBIDDEN 이 된다.
	 *
	 * <p>CookieCsrfTokenRepository 는 토큰을 처음 꺼낼 때 XSRF-TOKEN 쿠키를 만든다. handle 에서 토큰을 한 번 꺼내 두어, 쿠키가
	 * 없던 사용자도 첫 응답에서 쿠키를 받게 한다.
	 */
	static final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

		private final CsrfTokenRequestHandler plainHandler = new CsrfTokenRequestAttributeHandler();

		@Override
		public void handle(HttpServletRequest request, HttpServletResponse response,
			Supplier<CsrfToken> csrfToken) {
			this.plainHandler.handle(request, response, csrfToken);
			// 토큰을 꺼내야 저장소가 XSRF-TOKEN 쿠키를 응답에 싣는다.
			csrfToken.get();
		}

		@Override
		public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
			return this.plainHandler.resolveCsrfTokenValue(request, csrfToken);
		}
	}

	@Bean
	public CorsConfigurationSource corsConfigurationSource() {
		CorsConfiguration configuration = new CorsConfiguration();

		// 허용할 출처. 환경마다 yml 의 app.cors.allowed-origins 에 정확한 주소로 적는다(와일드카드 없음).
		configuration.setAllowedOrigins(corsProperties.allowedOrigins());
		// 허용할 HTTP 메서드
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
		// 허용할 헤더
		configuration.setAllowedHeaders(List.of("*"));
		// 자격증명 허용 (쿠키, Authorization 헤더 등)
		configuration.setAllowCredentials(true);
		// 브라우저에서 접근할 수 있는 응답 헤더
		configuration.setExposedHeaders(List.of("Authorization"));
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
