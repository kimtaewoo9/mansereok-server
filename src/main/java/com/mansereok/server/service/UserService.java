package com.mansereok.server.service;

import com.mansereok.server.entity.Gender;
import com.mansereok.server.entity.Result;
import com.mansereok.server.entity.SocialType;
import com.mansereok.server.entity.User;
import com.mansereok.server.repository.ResultRepository;
import com.mansereok.server.repository.UserRepository;
import com.mansereok.server.service.request.ProfileUpdateRequestDto;
import com.mansereok.server.service.response.InterpretationResultResponse;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

	private final UserRepository userRepository;
	private final ResultRepository resultRepository;

	private final PasswordEncoder passwordEncoder;


	/**
	 * 새로운 사용자를 등록한다.
	 *
	 * @param name     사용자명
	 * @param password 평문 비밀번호 (암호화되어 저장됨)
	 * @param email    이메일
	 * @return 생성된 사용자 엔티티
	 */
	public User createUser(String name, String email, String password) {
		if (userRepository.existsByEmail(email)) {
			throw new RuntimeException("이미 존재하는 이메일 입니다: " + email);
		}

		return userRepository.save(
			User.create(
				email,
				name,
				passwordEncoder.encode(password),
				email,
				true
			));
	}

	public User findByUsername(String username) {
		log.info("사용자 이메일: " + username);
		return userRepository.findByUsername(username)
			.orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: " + username));
	}

	public User getUserBySocialId(String socialId) {
		return userRepository.findBySocialId(socialId)
			.orElse(null);
	}

	// Oauth를 통한 회원가입
	public User registerWithOauth(String username, String email, String name,
		String sub, SocialType socialType) {
		if (userRepository.existsByEmail(email)) {
			throw new RuntimeException("이미 존재하는 이메일 입니다: " + email);
		}
		return userRepository.save(
			User.createByOauth(
				sub, // id 로 social id 를 사용함 .
				name, // 사용자 이름
				email,
				sub, // socialId
				socialType
			)
		);
	}

	@Transactional(readOnly = true)
	public User getUserById(Long userId) {
		return userRepository.findById(userId)
			.orElseThrow(() -> new RuntimeException("사용자를 찾을 수 없습니다: ID " + userId));
	}

	@Transactional
	public User updateUserProfile(String username, ProfileUpdateRequestDto requestDto) {
		User user = findByUsername(username);

		if (requestDto.getName() != null && !requestDto.getName().isBlank()) {
			user.setName(requestDto.getName());
		}
		if (requestDto.getBirthDate() != null) {
			user.setBirthDate(requestDto.getBirthDate());
		}
		if (requestDto.getGender() != null) {
			user.setGender(Gender.valueOf(requestDto.getGender()));
		}

		return userRepository.save(user);
	}

	@Transactional(readOnly = true)
	public InterpretationResultResponse getInterpretationResult(Long resultId, String username) {
		User user = findByUsername(username);

		Result result = resultRepository.findById(resultId)
			.orElseThrow(() -> new RuntimeException("result not found"));

		if (!result.getUserId().equals(user.getId())) {
			log.warn("다른 사람의 정보에 접근 시도. 접근 ID: {}", user.getId());
			throw new AccessDeniedException("다른 사람의 리소스에 접근할 수 없습니다.");
		}

		return InterpretationResultResponse.create(result);
	}

	@Transactional(readOnly = true)
	public List<InterpretationResultResponse> getInterpretationResults(String username) {
		User user = findByUsername(username);
		List<Result> results = resultRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId());

		return results.stream()
			.map(InterpretationResultResponse::create)
			.collect(Collectors.toList());
	}
}
