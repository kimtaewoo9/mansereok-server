package com.mansereok.server.domain.auth.service.oauth;

import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.domain.user.service.UserService;
import com.mansereok.server.global.exception.DuplicateEmailException;
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
 * <p>찾을 때 없던 계정이 가입하는 사이에 생길 수 있다. 같은 소셜 계정의 첫 로그인이 두 탭에서 동시에 들어오면 둘 다 1·2단계에서
 * 계정을 찾지 못하고 가입하러 간다. 뒤에 저장하는 쪽은 users 의 UNIQUE 에 걸려 DuplicateEmailException 을 받는데, 이때 같은
 * 규칙으로 다시 찾아 먼저 가입한 계정으로 로그인시킨다. 다시 찾아도 없으면(다른 제공자 가입자와 username 이 겹친 경우 등) 그
 * 예외를 그대로 던져 409 로 끝난다.
 *
 * <p>이렇게 먼저 가입한 계정으로 들어간 요청은 이번에 가입한 것이 아니므로 newlyRegistered 가 false 다. 방금 만들어져 프로필이 비어
 * 있는 계정이어도 로그인 응답의 isNewUser 는 false 다. 프로필 조회 응답의 newUser(profileIncomplete)는 프로필 빈칸으로 정하므로
 * true 다.
 *
 * <p>{@link #loginOrRegister} 는 트랜잭션 없이 불려야 한다. 거절된 가입의 롤백과 다시 찾기가 각자의 트랜잭션에서 돌아야 하기
 * 때문이다. 바깥 트랜잭션 안에서 부르면 가입 저장이 그 트랜잭션에 합류해, UNIQUE 위반의 롤백이 바깥 트랜잭션까지 롤백 전용으로
 * 만든다.
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

		Optional<User> existingUser = findExistingAccount(profile);
		if (existingUser.isPresent()) {
			return new OauthLoginResult(existingUser.get(), false);
		}
		try {
			return new OauthLoginResult(userService.registerWithOauth(profile), true);
		} catch (DuplicateEmailException e) {
			return new OauthLoginResult(findAccountRegisteredMeanwhile(profile, e), false);
		}
	}

	private Optional<User> findExistingAccount(OauthProfile profile) {
		return userRepository
			.findBySocialTypeAndSocialId(profile.socialType(), profile.socialId())
			.or(() -> findByTrustedEmail(profile));
	}

	/**
	 * 가입이 이미 있는 계정과 겹쳐 거절된 뒤, 처음과 같은 규칙으로 계정을 다시 찾는다. 거절한 저장은 먼저 저장한 요청이 커밋될 때까지
	 * 기다렸다가 난 것이라, 새로 여는 조회는 그 계정을 본다.
	 *
	 * @throws DuplicateEmailException 다시 찾아도 계정이 없을 때 받은 예외를 그대로 던진다
	 */
	private User findAccountRegisteredMeanwhile(OauthProfile profile, DuplicateEmailException e) {
		User user = findExistingAccount(profile).orElseThrow(() -> e);
		log.info("가입하는 사이 먼저 생긴 계정으로 {} 로그인: userId={}", profile.socialType(), user.getId());
		return user;
	}

	private Optional<User> findByTrustedEmail(OauthProfile profile) {
		Optional<User> linkedUser = profile.trustedEmail().flatMap(userRepository::findByEmail);
		linkedUser.ifPresent(user ->
			log.info("같은 이메일의 기존 계정으로 {} 로그인: userId={}", profile.socialType(), user.getId()));
		return linkedUser;
	}
}
