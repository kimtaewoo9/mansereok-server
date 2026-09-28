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
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.review.repository.ReviewRepository;
import com.mansereok.server.domain.user.dto.request.ProfileUpdateRequestDto;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.SocialType;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.event.PasswordResetRequestedEvent;
import com.mansereok.server.domain.user.event.UserRegisteredEvent;
import com.mansereok.server.domain.user.event.UserWithdrawnEvent;
import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import com.mansereok.server.domain.user.repository.UserRepository;
import com.mansereok.server.global.exception.DuplicateEmailException;
import com.mansereok.server.global.exception.UniqueConstraintViolations;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * 회원 가입·조회·수정·탈퇴.
 *
 * <p>가입·탈퇴 알림(Discord·Slack)은 여기서 직접 보내지 않는다. 가입·탈퇴 트랜잭션 안에서 {@link UserRegisteredEvent},
 * {@link UserWithdrawnEvent} 를 발행하고, UserNotificationListener 가 커밋된 뒤에 보낸다. 그래서 알림을 기다리는 동안 행 잠금을
 * 쥐지 않고, 롤백된 가입·탈퇴에는 알림이 가지 않는다. 다만 알림이 끝날 때까지 DB 커넥션은 아직 쥔다(UserNotificationListener 참고).
 *
 * <p>가입은 메서드에 {@code @Transactional} 을 붙이지 않고 {@link TransactionTemplate} 으로 저장 구간만 트랜잭션으로 묶는다.
 * 비밀번호 암호화(BCrypt)를 트랜잭션 전에 끝내고, 트랜잭션이 끝난 뒤 밖에서 UNIQUE 위반을 DuplicateEmailException 으로 바꾸기
 * 위해서다.
 *
 * <p>같은 회원의 행은 리프레시 토큰 → users → 재설정 토큰 순서로 잠근다. 탈퇴(deleteUser), 비밀번호 재설정 요청
 * (requestPasswordReset)·확인(resetPassword)이 이 순서를 따른다. 로그인과 토큰 재발급(RefreshTokenService.generateRefreshToken)은
 * 리프레시 토큰을 지운 뒤 새 토큰을 넣으면서 외래 키 확인으로 users 행을 공유 잠금하므로 이미 이 순서다. 순서를 달리 잡은 두 요청이
 * 겹치면 서로의 잠금을 기다리다 MySQL 이 한쪽을 교착(1213)으로 롤백하고, 그 요청은 503 을 받는다.
 *
 * <p>순서만 맞춰서는 회원에게 리프레시 토큰 행이 하나도 없을 때 교착이 남는다. REPEATABLE READ(MySQL 기본값)에서는 0행을 고치거나
 * 지우는 문장도 user_id 인덱스의 그 자리에 틈 잠금을 건다. 그사이 로그인이 users 행을 공유 잠금하고 그 틈에 새 토큰을 넣으려다
 * 기다리면, 뒤이어 users 행을 잠그려는 쪽과 서로를 기다린다. READ COMMITTED 에서는 틈 잠금을 걸지 않아 로그인이 먼저 넣고 커밋한다.
 * 그래서 리프레시 토큰을 먼저 다루는 확인과 탈퇴도 요청처럼 READ COMMITTED 로 돈다. READ COMMITTED 트랜잭션의 쓰기는 binlog_format
 * 이 STATEMENT 면 MySQL 이 1665 로 거절하므로, 운영 DB 의 binlog_format 이 ROW 나 MIXED 인지 배포 전에 확인한다.
 */
@Service
@Slf4j
public class UserService {

	private static final String EMAIL_SIGNUP_PATH = "일반 회원가입";

	private final UserRepository userRepository;
	private final ResultRepository resultRepository;
	private final CompatibilityResultRepository compatibilityResultRepository;
	private final RefreshTokenRepository refreshTokenRepository;

	private final OrderRepository orderRepository;
	private final PaymentRepository paymentRepository;

	private final PasswordResetTokenRepository passwordResetTokenRepository;

	private final PasswordEncoder passwordEncoder;

	private final EmailService emailService;
	private final ReviewRepository reviewRepository;

	private final ApplicationEventPublisher eventPublisher;
	private final TransactionTemplate transactionTemplate;
	private final Clock clock;

	public UserService(
		UserRepository userRepository,
		ResultRepository resultRepository,
		CompatibilityResultRepository compatibilityResultRepository,
		RefreshTokenRepository refreshTokenRepository,
		OrderRepository orderRepository,
		PaymentRepository paymentRepository,
		PasswordResetTokenRepository passwordResetTokenRepository,
		PasswordEncoder passwordEncoder,
		EmailService emailService,
		ReviewRepository reviewRepository,
		ApplicationEventPublisher eventPublisher,
		PlatformTransactionManager transactionManager,
		Clock clock
	) {
		this.userRepository = userRepository;
		this.resultRepository = resultRepository;
		this.compatibilityResultRepository = compatibilityResultRepository;
		this.refreshTokenRepository = refreshTokenRepository;
		this.orderRepository = orderRepository;
		this.paymentRepository = paymentRepository;
		this.passwordResetTokenRepository = passwordResetTokenRepository;
		this.passwordEncoder = passwordEncoder;
		this.emailService = emailService;
		this.reviewRepository = reviewRepository;
		this.eventPublisher = eventPublisher;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/**
	 * 이메일로 새 회원을 등록한다. 이메일 가입은 username 도 이메일이다.
	 *
	 * @param name     사용자명
	 * @param password 평문 비밀번호 (암호화되어 저장됨)
	 * @param email    이메일
	 * @return 생성된 사용자 엔티티
	 * @throws DuplicateEmailException 같은 이메일의 계정이 이미 있을 때. 같은 이메일 가입이 동시에 들어와 먼저 저장된 가입이 있을 때도
	 *                                 이 예외다(409).
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
		// BCrypt 는 한 번에 수십~100ms 걸린다. 트랜잭션을 열기 전에 끝내 그동안 DB 커넥션을 쥐지 않는다.
		String encodedPassword = passwordEncoder.encode(password);
		User newUser = User.create(
			email,
			name,
			encodedPassword,
			email,
			birthDate,
			gender,
			true,
			isPrivacyAgreed,
			isMarketingAgreed
		);

		User savedUser = saveNewUser(newUser, EMAIL_SIGNUP_PATH, "이미 존재하는 이메일 입니다: " + email);

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
	 *
	 * @throws DuplicateEmailException 저장이 이미 있는 계정의 이메일·username·(socialType, socialId) 와 겹칠 때. 같은 소셜 계정의
	 *                                 첫 로그인이 동시에 들어온 경우라면 OauthLoginService 가 먼저 가입한 계정을 다시 찾아 로그인시킨다.
	 */
	public User registerWithOauth(OauthProfile profile) {
		User newUser = User.createByOauth(
			oauthUsername(profile),
			profile.name(),
			profile.trustedEmail().orElse(null),
			profile.socialId(),
			profile.socialType()
		);

		User savedUser = saveNewUser(newUser, profile.socialType().name() + " OAuth",
			"이미 가입된 계정과 겹쳐 가입할 수 없습니다.");

//		emailService.sendWelcomeEmail(savedUser.getEmail(), savedUser.getName());

		return savedUser;
	}

	/**
	 * 소셜 가입 계정의 username. 네이버는 무작위 10자리 대문자, 나머지 제공자는 제공자의 사용자 번호를 그대로 쓴다. 예전 컨트롤러의
	 * 규칙을 그대로 옮겼다. username 은 가입할 때 한 번 저장되고 다시 계산하지 않으므로, 규칙을 바꿔도 이미 가입한 사용자에게는
	 * 영향이 없다.
	 *
	 * <p>남는 위험: 제공자가 달라도 사용자 번호가 같으면 username 이 겹칠 수 있다. uk_users_username 이 걸린 DB 에서는 뒤에 오는
	 * 가입의 저장이 UNIQUE 위반으로 거절되어 DuplicateEmailException(409)으로 끝난다. 그 사용자는 OauthLoginService 가 다시 찾아도
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
	 * 새 회원을 트랜잭션 하나로 저장하고, 같은 트랜잭션 안에서 가입 이벤트를 발행한다. 가입 알림은 커밋 뒤에 나간다.
	 *
	 * <p>이메일이 있으면 먼저 existsByEmail 로 확인해 흔한 중복(이미 가입한 이메일로 다시 가입)을 빨리 거절한다. 이 확인은 같은 가입이
	 * 동시에 두 번 들어오면 둘 다 지나칠 수 있어서, 마지막은 users 의 UNIQUE(uk_users_email, uk_users_username,
	 * uk_users_social_id_type)가 막는다. saveAndFlush 로 INSERT 를 이 트랜잭션 안에서 실행하므로 위반은 여기서 난다.
	 *
	 * <p>트랜잭션이 롤백된 뒤 밖에서 UNIQUE 위반일 때만 DuplicateEmailException 으로 바꿔 409 가 되게 한다. NOT NULL·길이 초과처럼
	 * 다른 제약 위반은 요청이 잘못됐거나 코드가 틀린 것이므로 바꾸지 않고 그대로 던진다.
	 *
	 * @param duplicateMessage 이미 있는 계정과 겹칠 때 DuplicateEmailException 에 담을 메시지
	 */
	private User saveNewUser(User newUser, String signupPath, String duplicateMessage) {
		try {
			return transactionTemplate.execute(status -> {
				if (newUser.getEmail() != null && userRepository.existsByEmail(newUser.getEmail())) {
					throw new DuplicateEmailException(duplicateMessage);
				}
				User savedUser = userRepository.saveAndFlush(newUser);
				eventPublisher.publishEvent(UserRegisteredEvent.of(savedUser, signupPath));
				return savedUser;
			});
		} catch (DataIntegrityViolationException e) {
			if (UniqueConstraintViolations.isUniqueViolation(e)) {
				throw new DuplicateEmailException(duplicateMessage, e);
			}
			throw e;
		}
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

	/**
	 * 회원을 탈퇴시킨다. 주문·결제는 남기고 사용자 연결만 끊으며, 개인정보와 서비스 데이터는 지운다.
	 *
	 * <p>탈퇴 알림은 여기서 보내지 않고 탈퇴 이벤트만 발행한다. 알림은 커밋 뒤에 UserNotificationListener 가 보낸다. 트랜잭션
	 * 안에서 보내면 1 에서 잠근 주문·결제 행과 DB 커넥션을 쥔 채 외부 응답을 기다리고, 커밋이 실패해도 탈퇴 알림이 이미 나간다.
	 *
	 * <p>재설정 토큰은 users 행을 잠근 뒤에 지운다(잠금 순서는 클래스 설명). 예전처럼 먼저 지우면, users 행을 잠그고 재설정 토큰을
	 * 넣거나 바꾸려는 같은 회원의 재설정 요청과 서로를 기다리다 한쪽이 교착으로 롤백됐다.
	 *
	 * <p>READ COMMITTED 로 돈다(클래스 설명). 그래서 리프레시 토큰이 없는 회원이면 처음 지운 뒤 users 를 잠그기 전에 로그인이 새
	 * 토큰을 넣고 커밋할 수 있다. users 를 잠근 뒤 리프레시 토큰을 한 번 더 지워 그 토큰을 치운다. 치우지 않으면 users 삭제가 외래
	 * 키(ON DELETE CASCADE 가 없을 때)에 걸려 탈퇴가 500 으로 롤백된다. 그 틈에 로그인이 두 번 이어져, 뒤 로그인이 앞 로그인의 토큰을
	 * 지운 채 users 잠금을 기다리면 두 번째 삭제가 그 토큰을 기다려 교착이 나고 탈퇴가 503 을 받는다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void deleteUser(String username) {
		User user = findByUsername(username);
		Long userId = user.getId();

		// 1. 주문/결제 내역은 보존 처리
		paymentRepository.detachUser(userId);
		orderRepository.detachUser(userId);

		// 2. 사용자를 가리키는 행을 사용자보다 먼저 지운다 (Hard Delete)
		//    순서: 리프레시 토큰 → 사주 결과·궁합 결과·리뷰 → users 행 잠금 → 리프레시 토큰 한 번 더 → 재설정 토큰 → (3) 사용자
		//    재설정 토큰과 리프레시 토큰은 users 를 외래 키로 가리켜, 남아 있으면 3 의 DELETE 가 막히고 탈퇴 전체가 롤백된다.
		//    리뷰는 작성자 이름·이메일 사본을 들고 있어 개인정보 파기를 위해 지운다.
		refreshTokenRepository.deleteByUser(user);       // 리프레시 토큰 삭제
		resultRepository.deleteAllByUserId(userId);      // 사주 결과 삭제
		compatibilityResultRepository.deleteAllByUserId(userId); // 궁합 결과 삭제
		reviewRepository.deleteAllByUserId(userId);      // 리뷰 삭제
		// 그사이 다른 요청이 먼저 탈퇴시켰으면 행이 없다
		userRepository.findByIdForUpdate(userId)
			.orElseThrow(() -> new EntityNotFoundException("사용자를 찾을 수 없습니다."));
		refreshTokenRepository.deleteByUser(user);       // 처음 지운 뒤 로그인이 넣고 커밋한 리프레시 토큰 삭제
		passwordResetTokenRepository.deleteAllByUserId(userId); // 재설정 토큰 삭제

		// 3. 유저 삭제 (Hard Delete)
		userRepository.delete(user);

		// 개인정보를 지운 뒤라 이메일(username)은 남기지 않는다.
		log.info("회원 탈퇴 처리 완료: userId={}", userId);

		eventPublisher.publishEvent(new UserWithdrawnEvent(userId, user.getName(), user.getEmail()));
	}

	/**
	 * 비밀번호 재설정 메일을 요청한다. 가입하지 않은 이메일이면 아무것도 하지 않는다(계정이 있는지 알려 주지 않는다). 소셜 가입자에게는
	 * 비밀번호가 없다는 안내 메일을 보낸다.
	 *
	 * <p>이메일 가입자는 사용자당 토큰 한 행을 둔다. 행이 없으면 새로 넣고(INSERT), 행의 토큰이 만료됐으면 토큰 값과 만료 시각만
	 * 바꾼다(UPDATE). 아직 만료 전이면 행을 그대로 두고 같은 토큰을 다시 보낸다. 그래서 메일로 나간 토큰은 만료 전까지 모두 DB 에 있고,
	 * 버튼을 두 번 누르거나 남이 이메일만 알고 요청을 되풀이해도 먼저 받은 링크가 죽지 않는다. 만료 시각도 늘리지 않는다. 예전처럼 지우고
	 * 넣으면 INSERT 가 미뤄진 DELETE 보다 먼저 나가 user_id UNIQUE 에 걸렸다.
	 *
	 * <p>같은 사용자의 요청이 겹치면(버튼 두 번 누르기) 둘 다 "행 없음" 을 보고 INSERT 해 하나가 UNIQUE 에 걸린다. 그래서 users 행을
	 * 잠가 한 줄로 세운다. 뒤 요청은 앞 요청이 커밋할 때까지 기다린 뒤 토큰 행을 읽는다. 이 읽기가 앞 요청이 넣은 행을 보려면
	 * READ COMMITTED 여야 한다. MySQL 기본값인 REPEATABLE READ 에서는 잠금을 기다리기 전의 첫 조회(findByEmail) 때 찍은 스냅숏을
	 * 계속 읽어 행이 없다고 보고 다시 INSERT 한다.
	 *
	 * <p>메일은 여기서 보내지 않고 {@link PasswordResetRequestedEvent} 를 발행한다. PasswordResetMailListener 가 커밋 뒤에 보내므로
	 * 커밋된 토큰의 링크만 나간다. 겹친 요청도 각자 메일을 보내고, 모두 같은 토큰이라 어느 메일의 링크로도 재설정할 수 있다.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
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

		// 같은 사용자의 요청을 한 줄로 세운다. 그사이 탈퇴해 행이 없으면 미가입 이메일과 같게 끝낸다.
		if (userRepository.findByIdForUpdate(user.getId()).isEmpty()) {
			return;
		}

		LocalDateTime now = LocalDateTime.now(clock);
		PasswordResetToken token;
		Optional<PasswordResetToken> existingToken = passwordResetTokenRepository.findByUserId(user.getId());
		if (existingToken.isEmpty()) {
			token = passwordResetTokenRepository.save(new PasswordResetToken(user, now));
		} else {
			token = existingToken.get();
			// 만료 전이면 바꾸지 않고 같은 토큰을 다시 보낸다
			if (token.isExpiredAt(now)) {
				token.reissue(now); // 커밋할 때 UPDATE 된다
			}
		}

		eventPublisher.publishEvent(new PasswordResetRequestedEvent(user.getId(), user.getEmail(), token.getToken()));
	}

	/**
	 * 메일로 받은 토큰으로 비밀번호를 바꾸고, 그 사용자의 리프레시 토큰을 모두 폐기한다. 폐기된 리프레시 토큰으로는 새 액세스 토큰을
	 * 받지 못한다. 다만 이미 발급된 액세스 토큰(JWT)은 필터가 DB 를 보지 않고 서명과 만료만 확인하므로 만료(access-token-expiration,
	 * 운영 30분)까지 그대로 통한다. 곧바로 끊으려면 users 에 비밀번호를 바꾼 시각을 두고 그보다 먼저 발급된(iat) 토큰을 거부해야 한다.
	 *
	 * <p>토큰은 조건부 DELETE(consume) 한 문장으로 쓴다. 같은 토큰으로 두 요청이 겹치면 DELETE 가 1 인 한 요청만 비밀번호를 바꾸고,
	 * 다른 요청은 400 을 받는다. 예전에는 이중 사용을 Hibernate 의 삭제 행 수 검사가 우연히 막아 뒤 요청이 500 을 받았다. 행 id 가
	 * 아니라 토큰 값으로 지운다. 만료된 토큰의 재요청은 행을 그대로 두고 값만 바꾸므로, 이 요청이 만료 직전의 토큰을 읽은 뒤 재요청이
	 * 커밋됐다면 id 로 지울 때는 옛 링크로 비밀번호가 바뀌고 새 링크가 죽는다.
	 *
	 * <p>리프레시 토큰 폐기 → users 행 잠금 → 재설정 토큰 쓰기 순서로 잠근다(클래스 설명). 토큰을 쓰지 못해 예외가 나면 앞의 폐기도
	 * 함께 롤백된다.
	 *
	 * <p>READ COMMITTED 로 돈다(클래스 설명). 리프레시 토큰이 없는 회원이면 폐기가 행도 틈도 잠그지 않아, 폐기와 users 잠금 사이에
	 * 로그인이 새 토큰을 넣고 커밋할 수 있다. 확인은 교착 없이 이어 가지만 그 토큰은 폐기되지 않고 남는다. 새 비밀번호가 커밋되기
	 * 전에 들어온 로그인이다. 리프레시 토큰이 있는 회원도, 로그인이 확인이 잠근 토큰을 기다렸다가 확인이 커밋한 뒤 새 토큰을 넣으면 그
	 * 토큰은 남는다. 재설정과 겹친 로그인까지 끊는 것은 위의 비밀번호를 바꾼 시각 확인이 맡을 몫이다.
	 *
	 * <p>없는 토큰과 만료된 토큰은 400 이다. 만료된 토큰은 지우지 않는다. 예외로 트랜잭션이 롤백되므로 지워도 반영되지 않는다. 같은
	 * 사용자가 다시 요청하면 그 행의 값이 바뀐다.
	 *
	 * @throws IllegalArgumentException 토큰이 없거나, 만료됐거나, 이미 쓰였거나, 그사이 회원이 탈퇴했을 때(400)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void resetPassword(String token, String newPassword) {
		LocalDateTime now = LocalDateTime.now(clock);
		PasswordResetToken resetToken = passwordResetTokenRepository.findByToken(token)
			.orElseThrow(() -> new IllegalArgumentException("유효하지 않은 토큰입니다."));

		if (resetToken.isExpiredAt(now)) {
			throw new IllegalArgumentException("만료된 토큰입니다. 다시 요청해주세요.");
		}

		// BCrypt 는 수십~100ms 걸린다. 행을 잠그기 전에 끝내 그동안 잠금을 쥐지 않는다.
		String encodedPassword = passwordEncoder.encode(newPassword);

		// 탈취된 리프레시 토큰으로 재설정 뒤에도 새 액세스 토큰을 받지 못하게 한다.
		refreshTokenRepository.revokeAllUserTokens(resetToken.getUser());

		// 그사이 탈퇴했으면 사용자 행이 없고, 재설정 토큰도 탈퇴가 함께 지웠다
		User user = userRepository.findByIdForUpdate(resetToken.getUser().getId())
			.orElseThrow(() -> new IllegalArgumentException("유효하지 않은 토큰입니다."));

		boolean consumed = passwordResetTokenRepository.consume(token, now) == 1;
		if (!consumed) {
			throw new IllegalArgumentException("이미 사용되었거나 만료된 토큰입니다.");
		}

		user.changePassword(encodedPassword);
	}
}
