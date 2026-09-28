package com.mansereok.server.domain.auth.service.oauth;

import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.UserService;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 네 소셜 로그인(구글·카카오·네이버·X)이 함께 쓰는 "계정 찾기, 없으면 가입" 규칙.
 *
 * <p>계정은 다음 순서로 찾는다.
 * <ol>
 *   <li>같은 제공자의 같은 사용자 번호로 가입한 계정</li>
 *   <li>제공자가 주인을 확인한 이메일이 있을 때만, 그 이메일로 가입한 기존 계정</li>
 *   <li>둘 다 없으면 새로 가입한다</li>
 * </ol>
 *
 * <p>이메일이 없거나 확인되지 않았으면 2단계를 건너뛴다. 이메일 없이 이메일로 찾으면 이메일이 비어 있는 다른 사람의 계정이
 * 잡힐 수 있고, 확인되지 않은 이메일로 찾으면 그 이메일을 가진 다른 사람의 계정에 들어갈 수 있다.
 *
 * <p>남는 위험: 2단계는 제공자 쪽 이메일 확인만 보고, 기존 계정의 이메일이 그 계정 주인에게 확인된 것인지는 보지 않는다. 이메일
 * 회원가입은 주소를 확인하지 않으므로, 누군가 남의 이메일로 먼저 이메일 가입을 해 두면 그 이메일의 진짜 주인이 소셜 로그인할 때
 * 먼저 가입한 사람의 계정으로 들어간다. 이메일 가입 때 주소를 확인하거나 소셜 계정을 붙이기 전에 기존 비밀번호를 확인하게 되면
 * 이 단계를 다시 정한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OauthLoginService {

	private final UserRepository userRepository;
	private final UserService userService;

	public OauthLoginResult loginOrRegister(OauthProfile profile) {
		Objects.requireNonNull(profile, "profile");

		Optional<User> existingUser = userRepository
			.findBySocialTypeAndSocialId(profile.socialType(), profile.socialId())
			.or(() -> findByTrustedEmail(profile));
		if (existingUser.isPresent()) {
			return new OauthLoginResult(existingUser.get(), false);
		}
		return new OauthLoginResult(userService.registerWithOauth(profile), true);
	}

	private Optional<User> findByTrustedEmail(OauthProfile profile) {
		Optional<User> linkedUser = profile.trustedEmail().flatMap(userRepository::findByEmail);
		linkedUser.ifPresent(user ->
			log.info("같은 이메일의 기존 계정으로 {} 로그인: userId={}", profile.socialType(), user.getId()));
		return linkedUser;
	}
}
