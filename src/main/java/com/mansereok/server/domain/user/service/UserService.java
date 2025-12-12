package com.mansereok.server.domain.user.service;

import com.mansereok.server.domain.interpret.dto.response.CompatibilityPageResponse;
import com.mansereok.server.domain.interpret.dto.response.InterpretationPageResponse;
import com.mansereok.server.domain.interpret.dto.response.InterpretationResultResponse;
import com.mansereok.server.domain.interpret.dto.response.ManseCompatibilityAnalysisResponse;
import com.mansereok.server.domain.interpret.dto.response.SajuHistoryResponseDto;
import com.mansereok.server.domain.interpret.entity.CompatibilityResult;
import com.mansereok.server.domain.interpret.entity.Result;
import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.notification.service.SlackNotificationService;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.dto.request.ProfileUpdateRequestDto;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.DuplicateEmailException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
	private final CompatibilityResultRepository compatibilityResultRepository;
	private final RefreshTokenRepository refreshTokenRepository;

	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;

	private final PasswordEncoder passwordEncoder;

	private final DiscordNotificationService discordNotificationService;
	private final SlackNotificationService slackNotificationService;

	private final EmailService emailService;

	/**
	 * 새로운 사용자를 등록한다.
	 *
	 * @param name     사용자명
	 * @param password 평문 비밀번호 (암호화되어 저장됨)
	 * @param email    이메일
	 * @return 생성된 사용자 엔티티
	 */
	public User createUser(
		String name,
		String email,
		String password,
		LocalDate birthDate,
		Gender gender,
		boolean isPrivacyAgreed,
		boolean isMarketingAgreed
	) {
		if (userRepository.existsByEmail(email)) {
			throw new DuplicateEmailException("이미 존재하는 이메일 입니다: " + email);
		}

		User savedUser = userRepository.save(
			User.create(
				email,
				name,
				passwordEncoder.encode(password),
				email,
				birthDate,
				gender,
				true,
				isPrivacyAgreed,
				isMarketingAgreed
			));

		discordNotificationService.sendUserCreatedNotification(
			savedUser.getName(),
			savedUser.getEmail(),
			savedUser.getId(),
			"일반 회원가입",
			savedUser.getCreatedAt()
		);

		slackNotificationService.sendUserCreatedNotification(
			savedUser.getName(),
			savedUser.getEmail(),
			savedUser.getId(),
			"일반 회원가입",
			savedUser.getCreatedAt()
		);

//		emailService.sendWelcomeEmail(savedUser.getEmail(), savedUser.getName());

		return savedUser;
	}

	public User findByEmail(String email) {
		return userRepository.findByEmail(email)
			.orElse(null);
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
			throw new DuplicateEmailException("이미 존재하는 이메일 입니다: " + email);
		}

		User savedUser = userRepository.save(
			User.createByOauth(
				name, //
				name,
				email,
				sub, // socialId
				socialType
			)
		);

		// 디스코드 알림 전송
		discordNotificationService.sendUserCreatedNotification(
			savedUser.getName(),
			savedUser.getEmail(),
			savedUser.getId(),
			socialType.name() + " OAuth",
			savedUser.getCreatedAt()
		);

		slackNotificationService.sendUserCreatedNotification(
			savedUser.getName(),
			savedUser.getEmail(),
			savedUser.getId(),
			"일반 회원가입",
			savedUser.getCreatedAt()
		);

//		emailService.sendWelcomeEmail(savedUser.getEmail(), savedUser.getName());

		return savedUser;
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

		if (requestDto.getGender() != null && !requestDto.getGender().isBlank()) {
			try {
				user.setGender(Gender.valueOf(requestDto.getGender()));
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("성별을 입력해야 합니다.");
			}
		}

		if (requestDto.getMarketingAgreed() != null) {
			user.setMarketingAgreed(requestDto.getMarketingAgreed());
		}

		if (user.isMarketingAgreed()) {
			if (user.getName() == null || user.getName().isBlank()) {
				throw new IllegalArgumentException("이름을 입력해주세요.");
			}
			if (user.getBirthDate() == null) {
				throw new IllegalArgumentException("생년월일을 입력해주세요.");
			}

			if (user.getGender() == null) {
				throw new IllegalArgumentException("성별을 선택해주세요.");
			}
		}

		return userRepository.save(user);
	}

	@Transactional(readOnly = true)
	public InterpretationResultResponse getInterpretationResult(Long resultId, String username) {
		User user = findByUsername(username);

		Result result = resultRepository.findById(resultId)
			.orElseThrow(() -> new RuntimeException("result not found. resultId: " + resultId));

		if (!result.getUserId().equals(user.getId())) {
			log.warn("다른 사람의 정보에 접근 시도. 접근 ID: {}, 접근하려는 ID: {}", user.getId(), result.getUserId());
			log.warn("resultId: {}", resultId); // resultId 전송 .
			throw new AccessDeniedException("다른 사람의 리소스에 접근할 수 없습니다.");
		}

		return InterpretationResultResponse.create(result);
	}

	@Transactional(readOnly = true)
	public List<SajuHistoryResponseDto> getCombinedSajuHistory(String username) {
		User user = findByUsername(username);
		Long userId = user.getId();

		// 단일 사주 목록 조회
		List<Result> sajuResults = resultRepository.findAllByUserIdOrderByCreatedAtDesc(userId);

		// 궁합 사주 목록 조회
		List<CompatibilityResult> compResults = compatibilityResultRepository.findByUserIdOrderByCreatedAtDesc(
			userId);

		// 두 리스트를 SajuHistoryResponseDto 스트림으로 변환
		Stream<SajuHistoryResponseDto> sajuStream = sajuResults.stream()
			.map(SajuHistoryResponseDto::new); // SajuHistoryResponseDto(Result result) 생성자 사용

		Stream<SajuHistoryResponseDto> compStream = compResults.stream()
			.map(
				SajuHistoryResponseDto::new); // SajuHistoryResponseDto(CompatibilityResult result) 생성자 사용

		// 두 스트림을 합치고, createdAt 기준으로 내림차순 정렬 (최신순)
		return Stream.concat(sajuStream, compStream)
			.sorted(Comparator.comparing(SajuHistoryResponseDto::getCreatedAt).reversed())
			.collect(Collectors.toList());
	}

	@Transactional(readOnly = true)
	public List<InterpretationPageResponse> getInterpretationResults(String username) {
		User user = findByUsername(username);
		List<Result> results = resultRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId());

		return results.stream()
			.map(result -> new InterpretationPageResponse(
				result.getProductName(),
				result.getCreatedAt().toLocalDate(),
				result.getStatus(),
				result.getPaymentId(),
				result.getId()
			))
			.collect(Collectors.toList());
	}

	@Transactional(readOnly = true)
	public List<CompatibilityPageResponse> getCompatibilityResults(String username) {
		// 1. username으로 User ID 조회
		User user = findByUsername(username);

		// 2. Repository에서 userId로 궁합 결과 목록 조회 (최신순)
		List<CompatibilityResult> results = compatibilityResultRepository.findByUserIdOrderByCreatedAtDesc(
			user.getId());

		// 3. Entity List -> DTO List로 변환
		return results.stream()
			.map(result -> new CompatibilityPageResponse(
				result.getProductName(),
				result.getCreatedAt().toLocalDate(),
				result.getStatus(),
				result.getPaymentId()
			))
			.collect(Collectors.toList());
	}

	@Transactional(readOnly = true)
	public ManseCompatibilityAnalysisResponse getCompatibilityResultDetail(Long resultId,
		String username) {
		User user = findByUsername(username);

		CompatibilityResult result = compatibilityResultRepository.findById(resultId)
			.orElseThrow(() -> new RuntimeException(
				"compatibility result not found. resultId: " + resultId));

		if (!result.getUserId().equals(user.getId())) {
			log.warn("다른 사람의 궁합 정보에 접근 시도. 접근 ID: {}, 접근하려는 ID: {}", user.getId(),
				result.getUserId());
			log.warn("resultId: {}", resultId);
			throw new AccessDeniedException("다른 사람의 리소스에 접근할 수 없습니다.");
		}

		// 궁합 DTO로 반환
		return new ManseCompatibilityAnalysisResponse(
			result.getId(),
			result.getPerson1Name(),
			result.getPerson1Ilgan(),
			result.getPerson2Name(),
			result.getPerson2Ilgan(),
			result.getInterpretation(),
			result.getCompatibilityScore(),
			result.getSummary(),
			result.getOgImageUrl()
		);
	}

	@Transactional
	public void deleteUser(String username) {
		User user = findByUsername(username);
		Long userId = user.getId();

		// 1. 주문/결제 내역은 보존 처리
		paymentRepository.detachUser(userId);
		orderRepository.detachUser(userId);

		// 2. 개인정보 및 서비스 데이터는 완전 삭제 (Hard Delete)
		refreshTokenRepository.deleteByUser(user);       // 리프레시 토큰 삭제
		resultRepository.deleteAllByUserId(userId);      // 사주 결과 삭제
		compatibilityResultRepository.deleteAllByUserId(userId); // 궁합 결과 삭제

		// 3. 유저 삭제 (Hard Delete)
		userRepository.delete(user);

		log.info("회원 탈퇴 처리 완료: userId={}, username={}", userId, username);

		// 알림 전송
		try {
			discordNotificationService.sendUserWithdrawnNotification(user.getName(),
				user.getEmail());
		} catch (Exception e) {
			log.warn("탈퇴 알림 전송 실패", e);
		}
	}
}
