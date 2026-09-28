package com.mansereok.server.domain.user.service;

import com.mansereok.server.domain.auth.PasswordResetTokenRepository;
import com.mansereok.server.domain.auth.entity.PasswordResetToken;
import com.mansereok.server.domain.auth.service.oauth.OauthProfile;
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
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.user.dto.request.ProfileUpdateRequestDto;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.DuplicateEmailException;
import jakarta.persistence.EntityNotFoundException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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

	private final PasswordResetTokenRepository passwordResetTokenRepository;

	private final PasswordEncoder passwordEncoder;

	private final DiscordNotificationService discordNotificationService;
	private final SlackNotificationService slackNotificationService;

	private final EmailService emailService;
	private final ReviewRepository reviewRepository;

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

		notifyUserCreated(savedUser, "일반 회원가입");

//		emailService.sendWelcomeEmail(savedUser.getEmail(), savedUser.getName());

		return savedUser;
	}

	/**
	 * 이메일로 계정을 찾는다. 이메일이 null 이거나 공백이면 조회하지 않고 빈 값을 돌려준다.
	 */
	public Optional<User> findByEmail(String email) {
		if (!StringUtils.hasText(email)) {
			return Optional.empty();
		}
		return userRepository.findByEmail(email);
	}

	/**
	 * 로그인한 사용자의 계정을 찾는다. 이메일 가입자는 username 이 이메일이라 로그와 예외 메시지에 username 을 넣지 않는다.
	 * 예외 메시지는 GlobalExceptionHandler 가 WARN 로그로 남긴다.
	 */
	public User findByUsername(String username) {
		return userRepository.findByUsername(username)
			.orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));
	}

	/**
	 * 소셜 로그인으로 새 계정을 만든다.
	 *
	 * <p>이메일은 제공자가 주인을 확인한 것만 저장하고, 없으면 null 로 둔다. 이메일 중복 검사도 저장할 이메일이 있을 때만 한다.
	 */
	public User registerWithOauth(OauthProfile profile) {
		String email = profile.trustedEmail().orElse(null);
		if (email != null && userRepository.existsByEmail(email)) {
			throw new DuplicateEmailException("이미 존재하는 이메일 입니다: " + email);
		}

		User savedUser = userRepository.save(
			User.createByOauth(
				oauthUsername(profile),
				profile.name(),
				email,
				profile.socialId(),
				profile.socialType()
			)
		);

		notifyUserCreated(savedUser, profile.socialType().name() + " OAuth");

//		emailService.sendWelcomeEmail(savedUser.getEmail(), savedUser.getName());

		return savedUser;
	}

	/**
	 * 소셜 가입 계정의 username. 네이버는 무작위 10자리 대문자, 나머지 제공자는 제공자의 사용자 번호를 그대로 쓴다. 예전 컨트롤러의
	 * 규칙을 그대로 옮겼다. username 은 가입할 때 한 번 저장되고 다시 계산하지 않으므로, 규칙을 바꿔도 이미 가입한 사용자에게는
	 * 영향이 없다.
	 *
	 * <p>남는 위험: 제공자가 달라도 사용자 번호가 같으면 username 이 겹칠 수 있다. uk_users_username 이 걸린 DB 에서는 뒤에 오는
	 * 가입의 저장이 DataIntegrityViolationException 으로 거절되어 500 으로 끝난다. 그 사용자는 (socialType, socialId) 로 다시 찾아도
	 * 계정이 없으므로 가입할 수 없다. UNIQUE 가 없으면 같은 username 행이 둘 생기고, username 으로 회원을 찾는 요청이 두 사용자
	 * 모두 실패한다. 새 가입부터 username 을 "제공자_번호" 로 만드는 것은 후속 과제로 남긴다.
	 */
	private static String oauthUsername(OauthProfile profile) {
		if (profile.socialType() == SocialType.NAVER) {
			return UUID.randomUUID().toString().substring(0, 10).toUpperCase();
		}
		return profile.socialId();
	}

	/**
	 * 가입 알림을 디스코드와 슬랙에 같은 가입 경로로 보낸다.
	 */
	private void notifyUserCreated(User user, String signupPath) {
		discordNotificationService.sendUserCreatedNotification(
			user.getName(),
			user.getEmail(),
			user.getId(),
			signupPath,
			user.getCreatedAt()
		);

		slackNotificationService.sendUserCreatedNotification(
			user.getName(),
			user.getEmail(),
			user.getId(),
			signupPath,
			user.getCreatedAt()
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
		if (requestDto.getBirthTime() != null) {
			user.setBirthTime(requestDto.getBirthTime());
		}
		if (requestDto.getBirthPlace() != null) {
			if (requestDto.getBirthPlace().isBlank()) {
				throw new IllegalArgumentException("태어난 장소는 공백일 수 없습니다.");
			}
			user.setBirthPlace(requestDto.getBirthPlace().trim());
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

		// marketingAgreed 여부와 무관하게 동일한 프로필 필수 검증을 적용한다.
		// 필수: 이름, 생년월일, 성별 / 선택: 태어난 시각, 태어난 장소
		if (user.getName() == null || user.getName().isBlank()) {
			throw new IllegalArgumentException("이름을 입력해주세요.");
		}
		if (user.getBirthDate() == null) {
			throw new IllegalArgumentException("생년월일을 입력해주세요.");
		}
		if (user.getGender() == null) {
			throw new IllegalArgumentException("성별을 선택해주세요.");
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

		reviewRepository.deleteAllByUserId(userId);

		// 3. 유저 삭제 (Hard Delete)
		userRepository.delete(user);

		// 개인정보를 지운 뒤라 이메일(username)은 남기지 않는다.
		log.info("회원 탈퇴 처리 완료: userId={}", userId);

		// 알림 전송
		try {
			discordNotificationService.sendUserWithdrawnNotification(user.getName(),
				user.getEmail());
		} catch (Exception e) {
			log.warn("탈퇴 알림 전송 실패", e);
		}
	}

	@Transactional
	public void requestPasswordReset(String email) {
		User user = userRepository.findByEmail(email)
			.orElse(null);

		// 미가입 이메일이면 조용히 리턴 (보안: 계정 존재 여부 노출 방지)
		if (user == null) {
			return;
		}

		// 소셜 로그인 사용자면 안내 메일 발송 후 리턴
		if (user.getSocialType() != null) {
			emailService.sendSocialLoginGuideEmail(user.getEmail(), user.getName());
			return;
		}

		// 기존에 발급된 토큰이 있다면 삭제 (한 사람이 여러 번 요청했을 때 처리)
		passwordResetTokenRepository.deleteByUserId(user.getId());

		// 새 토큰 생성 및 저장
		PasswordResetToken token = new PasswordResetToken(user);
		passwordResetTokenRepository.save(token);

		// 이메일 발송
		emailService.sendPasswordResetEmail(user.getEmail(), token.getToken());
	}

	@Transactional
	public void resetPassword(String token, String newPassword) {
		PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(token)
			.orElseThrow(() -> new IllegalArgumentException("유효하지 않은 토큰입니다."));

		if (resetToken.isExpired()) {
			passwordResetTokenRepository.delete(resetToken); // 만료된 토큰 삭제
			throw new IllegalArgumentException("만료된 토큰입니다. 다시 요청해주세요.");
		}

		User user = resetToken.getUser();
		user.setPassword(passwordEncoder.encode(newPassword)); // 비밀번호 암호화 후 저장

		// 사용된 토큰 삭제
		passwordResetTokenRepository.delete(resetToken);
	}
}
