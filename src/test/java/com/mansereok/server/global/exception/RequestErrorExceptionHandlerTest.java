package com.mansereok.server.global.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.method.annotation.ExceptionHandlerMethodResolver;

/**
 * 잘못된 요청과 DB 잠금 경합이 500 이 아니라 알맞은 상태 코드와 ErrorResponse 로 나가는지 확인한다.
 *
 * <p>운영과 같게 {@link GlobalExceptionHandler}(Exception catch-all 이 있다)와 {@link RequestErrorExceptionHandler} 를 함께
 * 등록한다. 등록 순서는 GlobalExceptionHandler 를 먼저 두어, catch-all 보다 먼저 보는 것이 등록 순서가 아니라 @Order 덕분임을
 * 확인한다.
 *
 * <p>우리 서버가 바깥 API 를 부르는 경우는 MockRestServiceServer 에 묶은 RestClient 로 흉내 낸다. 바깥 응답을 읽지 못한 오류도
 * 같은 HttpMessageNotReadableException 을 원인으로 품고 올라오기 때문이다.
 *
 * <p>이 파일은 결제 스택이 그대로 복사해 쓴다. 그래서 도메인 컨트롤러가 아니라 이 안의 {@link RequestErrorTestController} 만
 * 쓰고, 두 스택에 모두 있는 클래스만 참조한다.
 */
class RequestErrorExceptionHandlerTest {

	private static final String SECRET_IN_EXCEPTION = "db password=s3cr3t-in-exception";
	private static final String SQL_IN_EXCEPTION = "could not execute statement [Lock wait timeout exceeded]"
		+ " [select o1_0.id from orders o1_0 where o1_0.merchant_uid=? for update]";

	private static final String PROVIDER_PROFILE_URL = "https://provider.example/profile";
	private static final String TRUNCATED_PROVIDER_RESPONSE = "{\"tier\": \"GOL";

	private MockRestServiceServer providerServer;
	private RequestErrorTestController controller;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		RestClient.Builder providerClientBuilder = RestClient.builder();
		providerServer = MockRestServiceServer.bindTo(providerClientBuilder).build();
		controller = new RequestErrorTestController(providerClientBuilder.build());
		mockMvc = MockMvcBuilders.standaloneSetup(controller)
			.setControllerAdvice(new GlobalExceptionHandler(), new RequestErrorExceptionHandler())
			.build();
	}

	@Nested
	@DisplayName("쿼리 파라미터나 경로 변수가 잘못되면")
	class WhenParameterIsWrong {

		@Test
		@DisplayName("필수 파라미터가 없으면 400 MISSING_PARAMETER 와 빠진 파라미터 이름을 돌려준다")
		void missingParameter() throws Exception {
			mockMvc.perform(get("/test/items"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.errorCode").value("MISSING_PARAMETER"))
				.andExpect(jsonPath("$.message").value("필수 요청 파라미터 'categoryId' 가 없습니다."));
		}

		@Test
		@DisplayName("쿼리 파라미터가 숫자가 아니면 400 INVALID_PARAMETER 와 파라미터 이름·보낸 값을 돌려준다")
		void queryParameterTypeMismatch() throws Exception {
			mockMvc.perform(get("/test/items").param("categoryId", "abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"))
				.andExpect(jsonPath("$.message")
					.value("요청 파라미터 'categoryId' 의 값 'abc' 이 올바른 형식이 아닙니다."));
		}

		@Test
		@DisplayName("같은 파라미터를 여러 번 보내 형식이 틀리면 값은 빼고 파라미터 이름만 알려준다")
		void repeatedQueryParameterTypeMismatch() throws Exception {
			mockMvc.perform(get("/test/items").param("categoryId", "abc", "1"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"))
				.andExpect(jsonPath("$.message").value("요청 파라미터 'categoryId' 의 값이 올바른 형식이 아닙니다."));
		}

		@Test
		@DisplayName("경로 변수가 숫자가 아니면 400 INVALID_PARAMETER 를 돌려준다")
		void pathVariableTypeMismatch() throws Exception {
			mockMvc.perform(get("/test/items/abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"))
				.andExpect(jsonPath("$.message")
					.value("요청 파라미터 'itemId' 의 값 'abc' 이 올바른 형식이 아닙니다."));
		}

		@Test
		@DisplayName("파라미터에 붙인 제약(@Min(1))을 어기면 400 INVALID_PARAMETER 와 파라미터별 위반을 돌려준다")
		void parameterConstraintViolation() throws Exception {
			mockMvc.perform(get("/test/items").param("categoryId", "1").param("page", "0"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_PARAMETER"))
				.andExpect(jsonPath("$.message").value("요청 파라미터 값이 올바르지 않습니다."))
				.andExpect(jsonPath("$.errors.page").exists());
		}
	}

	@Nested
	@DisplayName("요청 본문을 읽을 수 없으면")
	class WhenBodyIsUnreadable {

		@ParameterizedTest(name = "[{index}] 본문 {0}")
		@ValueSource(strings = {"{", "{\"gender\": }", ""})
		@DisplayName("깨진 JSON 이나 빈 본문은 400 INVALID_REQUEST_BODY 이고 Jackson 원문을 응답에 담지 않는다")
		void brokenJson(String body) throws Exception {
			mockMvc.perform(post("/test/members").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST_BODY"))
				.andExpect(jsonPath("$.message").value("요청 본문을 읽을 수 없습니다. JSON 형식을 확인해주세요."))
				.andExpect(content().string(not(containsString("JSON parse error"))))
				.andExpect(content().string(not(containsString("com.mansereok"))));
		}

		@Test
		@DisplayName("enum 필드에 없는 값 'M' 을 보내면 400 INVALID_REQUEST_BODY 와 필드 이름·보낸 값을 돌려준다")
		void unknownEnumValue() throws Exception {
			mockMvc.perform(post("/test/members").contentType(MediaType.APPLICATION_JSON)
					.content("{\"name\": \"홍길동\", \"gender\": \"M\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST_BODY"))
				.andExpect(jsonPath("$.message").value("요청 본문의 gender 값 'M' 이 올바른 형식이 아닙니다."));
		}

		@Test
		@DisplayName("목록 안의 필드 값이 틀리면 members[1].gender 처럼 몇 번째 항목인지까지 알려준다")
		void unknownEnumValueInList() throws Exception {
			mockMvc.perform(post("/test/members/bulk").contentType(MediaType.APPLICATION_JSON)
					.content("{\"members\": [{\"name\": \"가\", \"gender\": \"FEMALE\"},"
						+ " {\"name\": \"나\", \"gender\": \"male\"}]}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_REQUEST_BODY"))
				.andExpect(jsonPath("$.message")
					.value("요청 본문의 members[1].gender 값 'male' 이 올바른 형식이 아닙니다."));
		}
	}

	@Nested
	@DisplayName("주소는 맞지만 요청 형태를 받을 수 없으면")
	class WhenRequestShapeIsNotSupported {

		@Test
		@DisplayName("지원하지 않는 메서드(PUT)는 405 METHOD_NOT_ALLOWED 와 지원하는 메서드를 담은 Allow 헤더를 돌려준다")
		void methodNotAllowed() throws Exception {
			mockMvc.perform(put("/test/members"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(header().string("Allow", "POST"))
				.andExpect(jsonPath("$.status").value(405))
				.andExpect(jsonPath("$.errorCode").value("METHOD_NOT_ALLOWED"))
				.andExpect(jsonPath("$.message").value("이 주소는 PUT 요청을 지원하지 않습니다."));
		}

		@Test
		@DisplayName("JSON 자리에 text/plain 을 보내면 415 UNSUPPORTED_MEDIA_TYPE 를 돌려준다")
		void unsupportedMediaType() throws Exception {
			mockMvc.perform(post("/test/members").contentType(MediaType.TEXT_PLAIN).content("홍길동"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(header().string("Accept", containsString("application/json")))
				.andExpect(jsonPath("$.status").value(415))
				.andExpect(jsonPath("$.errorCode").value("UNSUPPORTED_MEDIA_TYPE"))
				.andExpect(jsonPath("$.message").value("Content-Type 'text/plain' 은 지원하지 않습니다."));
		}
	}

	@Nested
	@DisplayName("DB 잠금을 얻지 못하면")
	class WhenLockIsNotAcquired {

		@ParameterizedTest(name = "[{index}] {0}")
		@ValueSource(strings = {"/test/lock-wait-timeout", "/test/deadlock"})
		@DisplayName("잠금 대기 초과·교착은 503 SERVER_BUSY 이고 SQL 이 든 예외 원문을 응답에 담지 않는다")
		void lockFailure(String path) throws Exception {
			mockMvc.perform(get(path))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.status").value(503))
				.andExpect(jsonPath("$.errorCode").value("SERVER_BUSY"))
				.andExpect(jsonPath("$.message").value("요청이 몰려 지금은 처리하지 못했습니다. 잠시 후 다시 시도해주세요."))
				.andExpect(content().string(not(containsString("merchant_uid"))))
				.andExpect(content().string(not(containsString("Lock wait timeout"))));
		}
	}

	@Nested
	@DisplayName("우리 서버가 바깥 API 응답을 읽지 못하면")
	class WhenProviderResponseIsUnreadable {

		@ParameterizedTest(name = "[{index}] 바깥 응답 {0}")
		@ValueSource(strings = {TRUNCATED_PROVIDER_RESPONSE, "{\"tier\": \"PLATINUM\"}"})
		@DisplayName("요청 본문 오류(400)가 아니라 catch-all 과 같은 500 으로 답하고 바깥 응답의 필드·값을 담지 않는다")
		void unreadableProviderResponse(String providerResponse) throws Exception {
			// given: RestClient 는 응답을 읽지 못하면 HttpMessageNotReadableException 을 RestClientException 으로 감싸 던진다
			providerServer.expect(requestTo(PROVIDER_PROFILE_URL))
				.andRespond(withSuccess(providerResponse, MediaType.APPLICATION_JSON));

			// when & then
			mockMvc.perform(get("/test/provider-profile"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"))
				.andExpect(jsonPath("$.message").value("서버 내부 오류가 발생했습니다. 잠시 후 다시 시도해주세요."))
				.andExpect(content().string(not(containsString("tier"))))
				.andExpect(content().string(not(containsString("PLATINUM"))));
		}

		@Test
		@DisplayName("그 오류를 RuntimeException 으로 한 번 더 감싸 올려도 500 으로 답한다")
		void unreadableProviderResponseWrappedInRuntimeException() throws Exception {
			// given
			providerServer.expect(requestTo(PROVIDER_PROFILE_URL))
				.andRespond(withSuccess(TRUNCATED_PROVIDER_RESPONSE, MediaType.APPLICATION_JSON));

			// when & then
			mockMvc.perform(get("/test/provider-profile-wrapped"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"));
		}

		@Test
		@DisplayName("컨트롤러 전용 처리기가 그 오류를 감싼 자기 예외를 맡으면 이 처리기보다 먼저 답한다(401)")
		void controllerSpecificHandlerAnswersBeforeThisHandler() throws Exception {
			// given: 컨트롤러 전용 처리기를 가장 나중에 등록해, 먼저 보는 것이 등록 순서가 아니라 @Order 덕분임을 확인한다
			MockMvc mockMvcWithLoginHandler = MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(new GlobalExceptionHandler(), new RequestErrorExceptionHandler(),
					new ProviderLoginExceptionHandler())
				.build();
			providerServer.expect(requestTo(PROVIDER_PROFILE_URL))
				.andRespond(withSuccess(TRUNCATED_PROVIDER_RESPONSE, MediaType.APPLICATION_JSON));

			// when & then
			mockMvcWithLoginHandler.perform(get("/test/provider-login"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.errorCode").value("PROVIDER_LOGIN_FAILED"));
		}
	}

	@Nested
	@DisplayName("로그는")
	class Logging {

		private final Logger handlerLogger = (Logger) LoggerFactory.getLogger(RequestErrorExceptionHandler.class);
		private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

		@BeforeEach
		void attachAppender() {
			logs.start();
			handlerLogger.addAppender(logs);
		}

		@AfterEach
		void detachAppender() {
			handlerLogger.detachAppender(logs);
		}

		@ParameterizedTest(name = "[{index}] gender 값 {0}")
		@ValueSource(strings = {"M", "male"})
		@DisplayName("본문 값의 형식이 틀리면 WARN 한 줄에 필드 이름만 남기고 보낸 값과 스택 트레이스는 남기지 않는다")
		void invalidBodyValue(String sentValue) throws Exception {
			// when
			mockMvc.perform(post("/test/members").contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\": \"홍길동\", \"gender\": \"" + sentValue + "\"}"));

			// then
			assertThat(logs.list).singleElement().satisfies(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.WARN);
				assertThat(event.getFormattedMessage()).contains("field=gender").doesNotContain(sentValue);
				assertThat(event.getThrowableProxy()).as("스택 트레이스를 남기지 않는다").isNull();
			});
		}

		@Test
		@DisplayName("필수 파라미터가 없으면 WARN 한 줄에 파라미터 이름을 남기고 스택 트레이스는 남기지 않는다")
		void missingParameter() throws Exception {
			// when
			mockMvc.perform(get("/test/items"));

			// then
			assertThat(logs.list).singleElement().satisfies(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.WARN);
				assertThat(event.getFormattedMessage()).contains("parameter=categoryId");
				assertThat(event.getThrowableProxy()).as("스택 트레이스를 남기지 않는다").isNull();
			});
		}

		@Test
		@DisplayName("DB 잠금을 얻지 못하면 WARN 한 줄을 남기고 스택 트레이스는 남기지 않는다")
		void lockFailure() throws Exception {
			// when
			mockMvc.perform(get("/test/lock-wait-timeout"));

			// then
			assertThat(logs.list).singleElement().satisfies(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.WARN);
				assertThat(event.getThrowableProxy()).as("스택 트레이스를 남기지 않는다").isNull();
			});
		}

		@Test
		@DisplayName("바깥 API 응답을 읽지 못한 오류는 원인을 찾을 수 있게 ERROR 로 스택 트레이스와 함께 남긴다")
		void unreadableProviderResponse() throws Exception {
			// given
			providerServer.expect(requestTo(PROVIDER_PROFILE_URL))
				.andRespond(withSuccess(TRUNCATED_PROVIDER_RESPONSE, MediaType.APPLICATION_JSON));

			// when
			mockMvc.perform(get("/test/provider-profile"));

			// then
			assertThat(logs.list).singleElement().satisfies(event -> {
				assertThat(event.getLevel()).isEqualTo(Level.ERROR);
				assertThat(event.getThrowableProxy()).as("던져진 예외의 스택 트레이스를 남긴다").isNotNull();
				assertThat(event.getThrowableProxy().getClassName()).isEqualTo(RestClientException.class.getName());
			});
		}
	}

	@Nested
	@DisplayName("이 처리기가 맡지 않는 예외는")
	class WhenExceptionIsNotARequestError {

		@Test
		@DisplayName("처리하지 못한 예외는 GlobalExceptionHandler 가 500 으로 답하고 예외 원문을 응답에 담지 않는다")
		void unexpectedExceptionStaysInternalServerError() throws Exception {
			mockMvc.perform(get("/test/unexpected"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.errorCode").value("INTERNAL_SERVER_ERROR"))
				.andExpect(content().string(not(containsString(SECRET_IN_EXCEPTION))));
		}

		@Test
		@DisplayName("헤더 누락(ServletRequestBindingException 계열)은 잡지 않고 다음 처리기로 넘긴다")
		void missingHeaderIsLeftToNextHandler() throws Exception {
			// given: 결제 스택 GlobalExceptionHandler 가 MISSING_HEADER 로 답하는 예외다
			MethodParameter headerParameter = new MethodParameter(
				RequestErrorTestController.class.getMethod("webhook", String.class), 0);
			MissingRequestHeaderException missingHeader = new MissingRequestHeaderException(
				"webhook-signature", headerParameter);
			ExceptionHandlerMethodResolver resolver = new ExceptionHandlerMethodResolver(
				RequestErrorExceptionHandler.class);

			// when & then
			assertThat(resolver.resolveMethodByThrowable(missingHeader))
				.as("이 처리기에는 헤더 누락을 받는 메서드가 없어야 한다")
				.isNull();
		}
	}

	/**
	 * 요청 오류를 재현하기 위한 테스트 전용 컨트롤러.
	 */
	@RestController
	static class RequestErrorTestController {

		private final RestClient providerClient;

		RequestErrorTestController(RestClient providerClient) {
			this.providerClient = providerClient;
		}

		@GetMapping("/test/items")
		public String items(@RequestParam Long categoryId,
			@RequestParam(defaultValue = "1") @Min(1) int page) {
			return "ok";
		}

		@GetMapping("/test/items/{itemId}")
		public String item(@PathVariable("itemId") Long itemId) {
			return "ok";
		}

		@PostMapping("/test/members")
		public String createMember(@RequestBody MemberRequest request) {
			return "ok";
		}

		@PostMapping("/test/members/bulk")
		public String createMembers(@RequestBody MembersRequest request) {
			return "ok";
		}

		@PostMapping("/test/webhook")
		public String webhook(@RequestHeader("webhook-signature") String signature) {
			return "ok";
		}

		// Hibernate 가 MySQL 1205(잠금 대기 초과)를 번역하면 이 타입이 된다(LockTimeoutTranslationMySqlTest).
		@GetMapping("/test/lock-wait-timeout")
		public String lockWaitTimeout() {
			throw new PessimisticLockingFailureException(SQL_IN_EXCEPTION);
		}

		// 교착(MySQL 1213)처럼 잠금을 얻지 못한 경우의 하위 타입이다.
		@GetMapping("/test/deadlock")
		public String deadlock() {
			throw new CannotAcquireLockException(SQL_IN_EXCEPTION);
		}

		@GetMapping("/test/unexpected")
		public String unexpected() {
			throw new RuntimeException(SECRET_IN_EXCEPTION);
		}

		// 소셜 로그인처럼 바깥 API 응답을 DTO 로 읽는다. 읽지 못하면 RestClientException 이 그대로 올라간다.
		@GetMapping("/test/provider-profile")
		public String providerProfile() {
			return providerClient.get().uri(PROVIDER_PROFILE_URL).retrieve()
				.toEntity(ProviderProfile.class).getBody().tier().name();
		}

		// XService 처럼 바깥 호출 오류를 맨 RuntimeException 으로 감싸 올린다.
		@GetMapping("/test/provider-profile-wrapped")
		public String providerProfileWrapped() {
			try {
				return providerProfile();
			} catch (RestClientException e) {
				throw new RuntimeException("제공자 프로필 조회 실패", e);
			}
		}

		// 로그인 실패 처리기가 따로 있는 컨트롤러처럼 바깥 호출 오류를 자기 예외로 감싸 올린다.
		@GetMapping("/test/provider-login")
		public String providerLogin() {
			try {
				return providerProfile();
			} catch (RestClientException e) {
				throw new ProviderLoginFailedException(e);
			}
		}
	}

	/**
	 * 컨트롤러 하나만 맡는 처리기(assignableTypes)를 흉내 낸다. 이런 처리기는 HIGHEST_PRECEDENCE 에 두고, 바깥 호출 오류를 감싼 자기
	 * 예외를 RequestErrorExceptionHandler 보다 먼저 받아야 한다.
	 */
	@Order(Ordered.HIGHEST_PRECEDENCE)
	@RestControllerAdvice(assignableTypes = RequestErrorTestController.class)
	static class ProviderLoginExceptionHandler {

		@ExceptionHandler(ProviderLoginFailedException.class)
		public ResponseEntity<ErrorResponse> handleProviderLoginFailed(ProviderLoginFailedException e) {
			ErrorResponse response = ErrorResponse.of(HttpStatus.UNAUTHORIZED.value(), "PROVIDER_LOGIN_FAILED",
				"로그인에 실패했습니다.");
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
		}
	}

	static class ProviderLoginFailedException extends RuntimeException {

		ProviderLoginFailedException(Throwable cause) {
			super("제공자 로그인 실패", cause);
		}
	}

	enum Gender {
		MALE, FEMALE
	}

	record MemberRequest(String name, Gender gender) {

	}

	record MembersRequest(List<MemberRequest> members) {

	}

	enum ProviderTier {
		GOLD, SILVER
	}

	record ProviderProfile(ProviderTier tier) {

	}
}
