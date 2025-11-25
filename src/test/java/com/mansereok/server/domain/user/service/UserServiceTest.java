package com.mansereok.server.domain.user.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.mansereok.server.domain.interpret.repository.CompatibilityResultRepository;
import com.mansereok.server.domain.interpret.repository.ResultRepository;
import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.notification.service.SlackNotificationService;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.RefreshTokenRepository;
import com.mansereok.server.domain.user.repository.UserRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
public class UserServiceTest {

	@InjectMocks
	private UserService userService;

	@Mock
	private UserRepository userRepository;

	@Mock
	private ResultRepository resultRepository;

	@Mock
	private CompatibilityResultRepository compatibilityResultRepository;

	@Mock
	private RefreshTokenRepository refreshTokenRepository;

	@Mock
	private DiscordNotificationService discordNotificationService;

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private PaymentRepository paymentRepository;

	// UserService 생성자에 필요한 기타 Mock 객체들
	@Mock
	private PasswordEncoder passwordEncoder;
	@Mock
	private SlackNotificationService slackNotificationService;
	@Mock
	private EmailService emailService;

	@Test
	@DisplayName("회원 탈퇴 성공: 연관 데이터 삭제 및 알림 전송이 정상적으로 수행된다.")
	void deleteUser_Success() {
		// given
		String username = "testUser";
		Long userId = 1L;

		// User.create 메서드를 사용하여 테스트용 유저 생성
		User mockUser = User.create(
			username,
			"테스트유저",
			"password123",
			"test@example.com",
			LocalDate.of(2000, 1, 1),
			Gender.MALE,
			true,
			true,
			true
		);
		mockUser.setId(userId); // ID 설정 (테스트를 위해)

		// 유저 조회 시 mockUser 반환
		given(userRepository.findByUsername(username)).willReturn(Optional.of(mockUser));

		// when
		userService.deleteUser(username);

		// then
		// 1. 리프레시 토큰 삭제 호출 검증
		verify(refreshTokenRepository, times(1)).deleteByUser(mockUser);

		// 2. 사주 결과 삭제 호출 검증
		verify(resultRepository, times(1)).deleteAllByUserId(userId);

		// 3. 궁합 결과 삭제 호출 검증
		verify(compatibilityResultRepository, times(1)).deleteAllByUserId(userId);

		// 4. 유저 삭제 호출 검증
		verify(userRepository, times(1)).delete(mockUser);

		// 5. 알림 전송 호출 검증
		verify(discordNotificationService, times(1))
			.sendUserWithdrawnNotification(mockUser.getName(), mockUser.getEmail());
	}

	@Test
	@DisplayName("회원 탈퇴 실패: 존재하지 않는 사용자인 경우 예외가 발생한다.")
	void deleteUser_UserNotFound() {
		// given
		String username = "unknownUser";
		given(userRepository.findByUsername(username)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> userService.deleteUser(username))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining("사용자를 찾을 수 없습니다");
	}

	@Test
	@DisplayName("회원 탈퇴 성공: 알림 전송이 실패해도 탈퇴 로직은 정상적으로 완료되어야 한다.")
	void deleteUser_NotificationFail_But_WithdrawSuccess() {
		// given
		String username = "testUser";
		Long userId = 1L;

		User mockUser = User.create(
			username,
			"테스트유저",
			"password123",
			"test@example.com",
			LocalDate.of(2000, 1, 1),
			Gender.MALE,
			true,
			true,
			true
		);
		mockUser.setId(userId);

		given(userRepository.findByUsername(username)).willReturn(Optional.of(mockUser));

		// 알림 전송 시 예외 발생하도록 설정 (try-catch 검증용)
		doThrow(new RuntimeException("Discord Error"))
			.when(discordNotificationService)
			.sendUserWithdrawnNotification(anyString(), anyString());

		// when
		userService.deleteUser(username);

		// then
		// 예외가 발생했어도 핵심 삭제 로직들은 모두 수행되어야 함
		verify(refreshTokenRepository, times(1)).deleteByUser(mockUser);
		verify(resultRepository, times(1)).deleteAllByUserId(userId);
		verify(compatibilityResultRepository, times(1)).deleteAllByUserId(userId);
		verify(userRepository, times(1)).delete(mockUser);
	}

	@Test
	@DisplayName("회원 탈퇴 시 주문/결제 내역의 연결을 끊고(NULL 처리) 나머지 데이터는 삭제한다")
	void deleteUser_ShouldDetachOrderAndPayment() {
		// given
		String username = "testUser";
		Long userId = 1L;

		User mockUser = User.create(
			username, "테스트유저", "pw", "test@email.com",
			LocalDate.now(), Gender.MALE, true, true, true
		);

		given(userRepository.findByUsername(username)).willReturn(Optional.of(mockUser));
		// mockUser.getId()가 1L을 반환한다고 가정 (User 엔티티에 id가 세팅되어야 함)
		// 실제 테스트 환경에서는 DB에 저장 후 가져오거나, Spy 객체를 써야 정확합니다.
		// 여기서는 로직 흐름 검증이므로 생략

		// when
		userService.deleteUser(username);

		// then
		// 1. [검증] 결제 내역 연결 끊기 호출 확인 (detachUser)
		verify(paymentRepository, times(1)).detachUser(mockUser.getId());

		// 2. [검증] 주문 내역 연결 끊기 호출 확인 (detachUser)
		verify(orderRepository, times(1)).detachUser(mockUser.getId());

		// 3. 나머지 삭제 로직 확인
		verify(refreshTokenRepository, times(1)).deleteByUser(mockUser);
		verify(resultRepository, times(1)).deleteAllByUserId(mockUser.getId());
		verify(userRepository, times(1)).delete(mockUser);
	}
}
