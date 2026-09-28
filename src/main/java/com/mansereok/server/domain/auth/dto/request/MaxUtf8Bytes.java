package com.mansereok.server.domain.auth.dto.request;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * 문자열을 UTF-8 로 바꾼 바이트 수가 {@link #value()} 이하인지 검사한다. null 은 통과시킨다(값이 있는지는 {@code @NotBlank} 가
 * 본다).
 *
 * <p>글자 수를 세는 {@code @Size(max)} 로는 한글처럼 한 글자가 여러 바이트인 입력의 바이트 수를 막지 못해서 따로 둔다. 비밀번호의
 * BCrypt 한도({@link PasswordRule#MAX_BYTES})를 검사하는 데 쓴다.
 */
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({FIELD, METHOD, PARAMETER, ANNOTATION_TYPE})
@Retention(RUNTIME)
public @interface MaxUtf8Bytes {

	/**
	 * 허용하는 최대 바이트 수(UTF-8 기준, 이 값 포함)
	 */
	int value();

	String message();

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
