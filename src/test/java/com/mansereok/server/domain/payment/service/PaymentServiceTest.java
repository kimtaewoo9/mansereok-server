package com.mansereok.server.domain.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.mansereok.server.domain.discount.service.DiscountCodeService;
import com.mansereok.server.domain.discount.service.DiscountCodeService.DiscountValidationResult;
import com.mansereok.server.domain.order.dto.request.OrderCreateRequest;
import com.mansereok.server.domain.order.entity.Order;
import com.mansereok.server.domain.order.repository.OrderRepository;
import com.mansereok.server.domain.product.entity.SubCategory;
import com.mansereok.server.domain.product.repository.SubCategoryRepository;
import com.mansereok.server.domain.user.entity.Gender;
import com.mansereok.server.domain.user.entity.User;
import com.mansereok.server.domain.user.repository.UserRepository;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

	@InjectMocks
	private PaymentService paymentService;

	@Mock
	private OrderRepository orderRepository;
	@Mock
	private UserRepository userRepository;
	@Mock
	private SubCategoryRepository subCategoryRepository;
	@Mock
	private DiscountCodeService discountCodeService;

	@Test
	@DisplayName("주문 생성 시 구매자 이름과 이메일이 Order 엔티티에 올바르게 저장되어야 한다")
	void createOrder_ShouldSaveBuyerInfo() {
		// given
		String username = "testUser";
		Long subCategoryId = 1L;
		String buyerName = "김태우";
		String buyerEmail = "taewoo@example.com";

		// 1. 사용자 Mock 설정 (User.create 팩토리 메서드 사용)
		User mockUser = User.create(username, buyerName, "password", buyerEmail,
			LocalDate.now(), Gender.MALE, true, true, true);

		given(userRepository.findByUsername(username)).willReturn(Optional.of(mockUser));

		// 2. 상품 Mock 설정 (protected 생성자 대응)
		SubCategory mockSubCategory = mock(SubCategory.class);
		given(subCategoryRepository.findById(subCategoryId)).willReturn(
			Optional.of(mockSubCategory));
		given(mockSubCategory.getPrice()).willReturn(10000);
		given(mockSubCategory.getId()).willReturn(subCategoryId);
		given(mockSubCategory.getTitle()).willReturn("인생 총운");

		// 3. 할인 검증 Mock 설정
		DiscountValidationResult mockResult = new DiscountValidationResult(10000, null, null);
		given(discountCodeService.validateAndCalculateDiscountForPayment(any(), anyInt(), any()))
			.willReturn(mockResult);

		// 4. 주문 저장 Mock 설정
		given(orderRepository.save(any(Order.class))).willAnswer(invocation -> {
			Order order = invocation.getArgument(0);
			// save 호출 시점에 buyerName, buyerEmail이 잘 들어있는지 확인
			return order;
		});

		// 요청 DTO 생성
		OrderCreateRequest request = new OrderCreateRequest();
		request.setSubCategoryId(subCategoryId);

		// when
		paymentService.createOrder(username, request);

		// then
		// ArgumentCaptor를 사용하여 실제로 save 메서드에 전달된 Order 객체를 포획
		ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
		verify(orderRepository, times(1)).save(orderCaptor.capture());

		Order savedOrder = orderCaptor.getValue();

		// 검증: Order 엔티티에 유저 정보(이름, 이메일)가 잘 박제되었는지 확인
		assertThat(savedOrder.getBuyerName()).isEqualTo(buyerName);
		assertThat(savedOrder.getBuyerEmail()).isEqualTo(buyerEmail);
	}
}
