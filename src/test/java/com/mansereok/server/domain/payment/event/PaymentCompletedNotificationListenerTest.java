package com.mansereok.server.domain.payment.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.mansereok.server.domain.notification.service.DiscordNotificationService;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.entity.OrderStatus;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.payment.entity.Payment;
import com.mansereok.server.domain.payment.entity.PaymentStatus;
import com.mansereok.server.domain.payment.repository.PaymentRepository;
import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@ExtendWith(MockitoExtension.class)
class PaymentCompletedNotificationListenerTest {

	private static final Long USER_ID = 1L;
	private static final Long SUB_CATEGORY_ID = 3L;
	private static final Long ORDER_ID = 10L;
	private static final Long PAYMENT_PK_ID = 100L;
	private static final String MERCHANT_UID = "order_test_001";
	private static final String PAYMENT_ID = "pay_test_001";
	private static final long AMOUNT = 9000L;
	private static final LocalDateTime PAID_AT = LocalDateTime.of(2026, 9, 21, 12, 30);

	@InjectMocks
	private PaymentCompletedNotificationListener listener;

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private PaymentRepository paymentRepository;
	@Mock
	private UserRepository userRepository;
	@Mock
	private SubCategoryRepository subCategoryRepository;
	@Mock
	private DiscordNotificationService discordNotificationService;

	private Order order;
	private Payment payment;

	@BeforeEach
	void setUp() {
		order = Order.create(MERCHANT_UID, USER_ID, SUB_CATEGORY_ID, 10000, (int) AMOUNT, "SALE10",
			null, OrderStatus.PENDING, "김태우", "taewoo@example.com");
		ReflectionTestUtils.setField(order, "id", ORDER_ID);
		order.markPaid(PAYMENT_ID, PAID_AT);

		payment = Payment.create(PAYMENT_ID, MERCHANT_UID, AMOUNT, PaymentStatus.PAID, ORDER_ID,
			USER_ID, SUB_CATEGORY_ID);
		ReflectionTestUtils.setField(payment, "id", PAYMENT_PK_ID);
	}

	private User user() {
		User user = User.create("testUser", "김태우", "password", "taewoo@example.com",
			LocalDate.of(1990, 1, 1), Gender.MALE, true, true, true);
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}

	private SubCategory subCategory() {
		// protected 생성자 대응. 공용 픽스처라 lenient 로 둔다.
		SubCategory subCategory = mock(SubCategory.class);
		lenient().when(subCategory.getTitle()).thenReturn("인생 총운");
		return subCategory;
	}

	private void givenOrderAndPayment() {
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
		given(paymentRepository.findById(PAYMENT_PK_ID)).willReturn(Optional.of(payment));
	}

	private static PaymentCompletedEvent event(long amount) {
		return new PaymentCompletedEvent(ORDER_ID, PAYMENT_PK_ID, amount);
	}

	@Test
	@DisplayName("정상 결제 이벤트는 주문·결제·사용자·상품을 다시 읽어 사용자 이름·이메일, 결제 금액, 상품명, 결제 시각, 할인 코드, 원가로 Discord 알림을 보낸다")
	void on_paidEvent_sendsDiscordNotificationWithReloadedDetails() {
		// given
		givenOrderAndPayment();
		SubCategory subCategory = subCategory();
		given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));

		// when
		listener.on(event(AMOUNT));

		// then
		verify(discordNotificationService).sendPaymentCompletedNotification(
			"김태우", "taewoo@example.com", AMOUNT, "인생 총운", PAID_AT, "SALE10", 10000);
	}

	@Test
	@DisplayName("amount 가 0 인 무료 결제 이벤트는 아무것도 조회하지 않고 알림도 보내지 않는다")
	void on_zeroAmount_doesNothing() {
		// when
		listener.on(event(0L));

		// then
		verifyNoInteractions(orderRepository, paymentRepository, userRepository,
			subCategoryRepository, discordNotificationService);
	}

	@Test
	@DisplayName("amount 가 null 이면 무료 결제로 보고 알림을 보내지 않는다")
	void on_nullAmount_doesNothing() {
		// when
		listener.on(new PaymentCompletedEvent(ORDER_ID, PAYMENT_PK_ID, null));

		// then
		verifyNoInteractions(orderRepository, paymentRepository, discordNotificationService);
	}

	@Test
	@DisplayName("사용자를 찾을 수 없으면 warn 로그만 남기고 알림을 보내지 않으며 예외도 던지지 않는다")
	void on_userNotFound_skipsNotificationWithoutThrowing() {
		// given
		givenOrderAndPayment();
		given(userRepository.findById(USER_ID)).willReturn(Optional.empty());
		SubCategory subCategory = subCategory();
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));
		Logger logger = (Logger) LoggerFactory.getLogger(PaymentCompletedNotificationListener.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);

		try {
			// when & then
			assertThatCode(() -> listener.on(event(AMOUNT))).doesNotThrowAnyException();
			verifyNoInteractions(discordNotificationService);
			assertThat(appender.list).anySatisfy(logEvent -> {
				assertThat(logEvent.getLevel()).isEqualTo(Level.WARN);
				assertThat(logEvent.getFormattedMessage()).contains("사용자(ID:" + USER_ID + ")");
			});
		} finally {
			logger.detachAppender(appender);
		}
	}

	@Test
	@DisplayName("상품을 찾을 수 없으면 알림을 보내지 않는다")
	void on_subCategoryNotFound_skipsNotification() {
		// given
		givenOrderAndPayment();
		given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(Optional.empty());

		// when
		listener.on(event(AMOUNT));

		// then
		verifyNoInteractions(discordNotificationService);
	}

	@Test
	@DisplayName("주문을 찾을 수 없으면 사용자·상품을 조회하지 않고 알림도 보내지 않는다")
	void on_orderNotFound_skipsNotification() {
		// given
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.empty());
		lenient().when(paymentRepository.findById(PAYMENT_PK_ID)).thenReturn(Optional.of(payment));

		// when
		listener.on(event(AMOUNT));

		// then
		verifyNoInteractions(userRepository, subCategoryRepository, discordNotificationService);
	}

	@Test
	@DisplayName("결제를 찾을 수 없으면 알림을 보내지 않는다")
	void on_paymentNotFound_skipsNotification() {
		// given
		given(orderRepository.findById(ORDER_ID)).willReturn(Optional.of(order));
		given(paymentRepository.findById(PAYMENT_PK_ID)).willReturn(Optional.empty());

		// when
		listener.on(event(AMOUNT));

		// then
		verifyNoInteractions(discordNotificationService);
	}

	@Test
	@DisplayName("탈퇴 처리로 주문의 userId 가 null 이면 사용자 조회 없이 알림을 건너뛴다")
	void on_orderWithoutUserId_skipsNotification() {
		// given
		ReflectionTestUtils.setField(order, "userId", null);
		givenOrderAndPayment();
		SubCategory subCategory = subCategory();
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));

		// when
		assertThatCode(() -> listener.on(event(AMOUNT))).doesNotThrowAnyException();

		// then
		verify(userRepository, never()).findById(any());
		verifyNoInteractions(discordNotificationService);
	}

	@Test
	@DisplayName("Discord 전송 중 예외가 나도 error 로그로 삼키고 밖으로 던지지 않는다")
	void on_discordFailure_isSwallowed() {
		// given
		givenOrderAndPayment();
		SubCategory subCategory = subCategory();
		given(userRepository.findById(USER_ID)).willReturn(Optional.of(user()));
		given(subCategoryRepository.findById(SUB_CATEGORY_ID)).willReturn(
			Optional.of(subCategory));
		willThrow(new RuntimeException("discord down")).given(discordNotificationService)
			.sendPaymentCompletedNotification(anyString(), anyString(), anyLong(), anyString(),
				any(), anyString(), anyInt());

		// when & then
		assertThatCode(() -> listener.on(event(AMOUNT))).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("DB 조회 중 예외가 나도 error 로그로 삼키고 밖으로 던지지 않는다")
	void on_repositoryFailure_isSwallowed() {
		// given
		given(orderRepository.findById(ORDER_ID)).willThrow(new RuntimeException("db down"));

		// when & then
		assertThatCode(() -> listener.on(event(AMOUNT))).doesNotThrowAnyException();
		verifyNoInteractions(discordNotificationService);
	}

	@Test
	@DisplayName("리스너 메서드는 커밋 뒤(AFTER_COMMIT) 비동기(notificationTaskExecutor)로 실행되도록 선언돼 있다")
	void on_isDeclaredAsAsyncAfterCommitListener() throws NoSuchMethodException {
		// when
		Method method = PaymentCompletedNotificationListener.class.getMethod("on",
			PaymentCompletedEvent.class);
		TransactionalEventListener txListener = method.getAnnotation(
			TransactionalEventListener.class);
		Async async = method.getAnnotation(Async.class);

		// then
		assertThat(txListener).isNotNull();
		assertThat(txListener.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
		assertThat(async).isNotNull();
		assertThat(async.value()).isEqualTo("notificationTaskExecutor");
	}
}
