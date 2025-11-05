package com.mansereok.server.domain.auth.dto.request;


import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class RegisterRequest {

	@NotBlank(message = "사용자명은 필수입니다")
	@Size(min = 3, max = 20, message = "사용자명은 3-20자 사이여야 합니다")
	private String name;

	@NotBlank(message = "이메일은 필수입니다")
	@Email(message = "올바른 이메일 형식이어야 합니다")
	private String email;

	@NotBlank(message = "비밀번호는 필수입니다")
	@Size(min = 6, message = "비밀번호는 최소 6자 이상이어야 합니다")
	private String password;

	private LocalDate birthDate;

	private Gender gender;

	private Role role = Role.USER;

	private boolean privacyPolicyAgreed;
}
