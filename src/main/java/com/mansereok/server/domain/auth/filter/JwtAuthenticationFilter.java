package com.mansereok.server.domain.auth.filter;

import com.mansereok.server.domain.auth.util.JwtUtil;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.JwtAccountMismatchException;
import com.mansereok.server.global.exception.JwtAuthenticationException;
import com.mansereok.server.global.exception.JwtErrorCode;
import com.mansereok.server.global.exception.JwtSignatureException;
import com.mansereok.server.global.exception.JwtTokenExpiredException;
import com.mansereok.server.global.exception.JwtTokenMalformedException;
import com.mansereok.server.global.exception.JwtTokenMissingException;
import com.mansereok.server.global.exception.JwtUnsupportedException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.MissingClaimException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	/**
	 * 토큰 처리에 실패했을 때 그 예외({@link JwtAuthenticationException})를 담아 두는 요청 속성 이름.
	 * JwtAuthenticationEntryPoint 가 이 속성을 읽어 응답을 만든다.
	 */
	public static final String JWT_EXCEPTION_ATTRIBUTE = "jwt.exception";

	// 토큰을 해석하지 않고 넘기는 요청. CORS 사전 요청(OPTIONS)과, 회원 로그인과 무관한 운영 경로(actuator)다.
	// 어느 경로를 로그인 없이 열지는 여기서 정하지 않고 SecurityConfig 한 곳에서만 정한다.
	private static final RequestMatcher SKIPPED_REQUESTS = new OrRequestMatcher(
		PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.OPTIONS, "/**"),
		PathPatternRequestMatcher.withDefaults().matcher("/actuator/**")
	);

	private final JwtUtil jwtUtil;
	private final UserRepository userRepository;

	@Override
	protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
		return SKIPPED_REQUESTS.matches(request);
	}

	@Override
	protected void doFilterInternal(
		@NonNull HttpServletRequest request,
		@NonNull HttpServletResponse response,
		@NonNull FilterChain filterChain) throws ServletException, IOException {

		// 토큰이 없으면 익명으로, 잘못된 토큰이면 예외를 요청 속성(JWT_EXCEPTION_ATTRIBUTE)에 남기고 익명으로 넘긴다.
		// 로그인 없이 여는 경로는 그대로 통과한다. 로그인이 필요한 경로면 JwtAuthenticationEntryPoint 가 이 필터가 남긴 예외를 읽어
		// 그 예외의 JwtErrorCode 로 답한다(만료·서명 불일치·발급자 불일치·빈 토큰은 401, 형식이 틀리거나 지원하지 않는 토큰은 400,
		// 예상하지 못한 오류인 INTERNAL_ERROR 는 500).
		try {
			String jwtToken = extractBearerToken(request);

			if (jwtToken != null) {
				validateAndProcessToken(jwtToken, request);
			}
		} catch (JwtAuthenticationException ex) {
			request.setAttribute(JWT_EXCEPTION_ATTRIBUTE, ex);
		} catch (Exception ex) {
			logger.error("JWT 인증 처리 중 예상치 못한 오류 발생", ex);
			request.setAttribute(JWT_EXCEPTION_ATTRIBUTE,
				new JwtAuthenticationException("JWT 처리 중 내부 오류가 발생했습니다.", ex,
					JwtErrorCode.INTERNAL_ERROR));
		}

		filterChain.doFilter(request, response);
	}

	private String extractBearerToken(HttpServletRequest request) {
		String bearerToken = request.getHeader("Authorization");

		if (bearerToken == null) {
			return null; // 토큰이 없는 것은 정상임.
		}

		if (!bearerToken.startsWith("Bearer ")) {
			throw new JwtTokenMalformedException("Authorization Header 는 'Bearer '로 시작해야 합니다.");
		}

		String token = bearerToken.substring(7);
		if (token.trim().isEmpty()) {
			throw new JwtTokenMissingException("Bearer Token이 비어있습니다.");
		}

		return token;
	}

	private void validateAndProcessToken(String jwtToken, HttpServletRequest request) {
		try {
			// 서명·발급자·만료를 모두 확인한다. 하나라도 틀리면 아래 catch 에서 오류 종류별 예외로 바꾼다.
			Claims claims = jwtUtil.parseVerifiedClaims(jwtToken);

			String username = claims.getSubject();
			String role = claims.get("role", String.class);

			if (username != null) {
				List<GrantedAuthority> authorities =
					Collections.singletonList(new SimpleGrantedAuthority(role));

				requireSameAccount(claims, username);

				UsernamePasswordAuthenticationToken authentication =
					new UsernamePasswordAuthenticationToken(
						username,
						null,
						authorities
					);

				authentication.setDetails(
					new WebAuthenticationDetailsSource().buildDetails(request));

				// 세션 정보 대신 JWT 토큰 정보로 덮어쓰기
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
		} catch (ExpiredJwtException e) {
			throw new JwtTokenExpiredException("JWT 토큰이 만료되었습니다.");
		} catch (UnsupportedJwtException e) {
			throw new JwtUnsupportedException("지원하지 않는 JWT 토큰입니다.");
		} catch (MalformedJwtException e) {
			throw new JwtTokenMalformedException("JWT 토큰 형식이 올바르지 않습니다.");
		} catch (SignatureException e) {
			throw new JwtSignatureException("JWT 토큰의 서명이 유효하지 않습니다.");
		} catch (IncorrectClaimException | MissingClaimException e) {
			// 서명 키는 같아도 이 서버가 발급하지 않은 토큰이다. 서명이 틀린 토큰과 똑같이 막는다.
			throw new JwtSignatureException("JWT 토큰의 발급자가 올바르지 않습니다.");
		} catch (IllegalArgumentException e) {
			throw new JwtTokenMalformedException("JWT 토큰이 비어있거나 올바르지 않습니다.");
		}
	}

	/**
	 * 토큰의 subject(username)가 지금도 토큰을 발급받은 그 계정을 가리키는지 확인한다.
	 *
	 * <p>이메일 가입자의 username 은 이메일이고, 탈퇴는 users 행을 지운다. 그래서 탈퇴한 뒤 같은 이메일로 다시 가입하면 username 은
	 * 같고 id 만 다른 새 계정이 생긴다. subject 만 보고 인증하면 탈퇴 전에 발급받아 아직 만료되지 않은(최대 30분) 토큰이 새 계정으로
	 * 통한다. 로그인·소셜 로그인·재발급이 토큰에 넣는 userId 는 계정이 사는 동안 바뀌지 않으므로, (id, username) 이 함께 맞는 계정이
	 * 있을 때만 인증한다. 계정이 없거나 id 가 다르거나 userId 클레임이 없으면 서명이 맞아도 거절한다(401 JWT_ACCOUNT_MISMATCH).
	 *
	 * <p>이 확인은 토큰을 실은 요청마다 users 를 기본 키로 한 번 읽는다. 로그인이 필요한 요청은 어차피 뒤에서 username 으로 회원을
	 * 읽으므로 더해지는 부담은 그 조회 하나다.
	 *
	 * <p>남는 위험: principal 은 여전히 username 이라, 이 확인과 컨트롤러의 username 조회 사이의 짧은 틈에 탈퇴와 재가입이 둘 다
	 * 커밋되면 그 요청은 새 계정으로 풀린다. 확인한 id 를 principal 로 넘기고 뒤 코드가 id 로 회원을 찾게 바꾸면 없어지는 틈이라
	 * 후속 과제로 남긴다.
	 */
	private void requireSameAccount(Claims claims, String username) {
		Object userId = claims.get("userId");
		if (!(userId instanceof Number id) || !userRepository.existsByIdAndUsername(id.longValue(), username)) {
			throw new JwtAccountMismatchException("토큰이 가리키는 계정을 찾을 수 없습니다.");
		}
	}
}
