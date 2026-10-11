package com.mansereok.server.domain.coupon.service;

import com.mansereok.server.domain.coupon.dto.CouponEventDto;
import com.mansereok.server.domain.coupon.entity.Coupon;
import com.mansereok.server.domain.coupon.entity.CouponTemplate;
import com.mansereok.server.domain.coupon.repository.CouponEventRow;
import com.mansereok.server.domain.coupon.repository.CouponRepository;
import com.mansereok.server.domain.coupon.repository.CouponTemplateRepository;
import com.mansereok.server.domain.discount.service.DiscountCodeService.DiscountValidationResult;
import com.mansereok.server.global.exception.PaymentException;
import com.mansereok.server.global.exception.UniqueConstraintViolations;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class CouponService {

	// 이벤트 목록의 유효 기간 문구에 쓰는 날짜 모양(예: 2026.12.31)
	private static final DateTimeFormatter VALID_PERIOD_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy.MM.dd");

	private final CouponRepository couponRepository;
	private final CouponTemplateRepository couponTemplateRepository;
	private final Clock clock;

	/**
	 * 발급 기간, 만료, 사용 시각을 판정할 "지금" 을 clock 으로 정한다. 스프링은 이 생성자로 ClockConfig 의 Clock 빈을 넣는다.
	 */
	@Autowired
	public CouponService(CouponRepository couponRepository, CouponTemplateRepository couponTemplateRepository,
		Clock clock) {
		this.couponRepository = couponRepository;
		this.couponTemplateRepository = couponTemplateRepository;
		this.clock = clock;
	}

	/**
	 * 시스템 기본 시간대의 시계로 "지금" 을 정한다. 시각을 고정할 필요가 없는 곳(리포지토리를 목으로 바꾼 테스트 등)에서 쓴다.
	 */
	public CouponService(CouponRepository couponRepository, CouponTemplateRepository couponTemplateRepository) {
		this(couponRepository, couponTemplateRepository, Clock.systemDefaultZone());
	}

	@Transactional
	public void downloadCoupon(Long userId, Long templateId) {
		// 1. 템플릿 조회
		CouponTemplate template = couponTemplateRepository.findByIdWithLock(templateId)
			.orElseThrow(() -> new PaymentException("존재하지 않는 쿠폰 이벤트입니다."));

		// 2. 이벤트 기간 검증
		LocalDateTime now = LocalDateTime.now(clock);
		if (now.isBefore(template.getIssueStartDate()) || now.isAfter(template.getIssueEndDate())) {
			throw new PaymentException("발급 기간이 아닙니다.");
		}

		// 3. 중복 발급 검증 (이미 받은 건지 확인)
		if (couponRepository.existsByUserIdAndTemplateId(userId, templateId)) {
			throw new PaymentException("이미 발급받은 쿠폰입니다.");
		}

		// 4. 선착순 재고 증가 및 검증 (Template 엔티티 내부 로직). 상한에 닿았으면 CouponSoldOutException(400)
		template.incrementIssueCount();

		// 5. 실제 쿠폰 생성 및 저장
		Coupon coupon = Coupon.createFromTemplate(template, userId, now);
		saveIssuedCoupon(coupon);
	}

	/**
	 * 발급한 쿠폰을 저장한다. 3번 확인과 이 INSERT 사이에 템플릿 잠금을 거치지 않은 경로로 같은 사용자·템플릿 쿠폰이 먼저 들어가
	 * 있으면 uk_coupons_user_template 에 걸린다. 그 UNIQUE 위반은 3번 확인과 같은 PaymentException("이미 발급받은
	 * 쿠폰입니다.")(400)으로 바꿔 던진다. NOT NULL·길이 초과 같은 다른 무결성 위반은 "이미 받았다" 는 안내가 틀리므로 그대로
	 * 던진다(500). 어느 쪽이든 예외를 다시 던지므로 올린 발급 수와 함께 트랜잭션이 롤백된다.
	 *
	 * <p>Coupon 의 id 는 IDENTITY 라 save 도 곧바로 INSERT 하지만, 그 전제에 기대지 않고 위반을 이 자리에서 받으려고
	 * saveAndFlush 로 바로 보낸다. INSERT 가 커밋 때로 미뤄지면 트랜잭션을 끝내는 쪽에서 예외가 나서 여기서 바꿀 수 없다.
	 */
	private void saveIssuedCoupon(Coupon coupon) {
		try {
			couponRepository.saveAndFlush(coupon);
		} catch (DataIntegrityViolationException e) {
			if (!UniqueConstraintViolations.isUniqueViolation(e)) {
				throw e;
			}
			// 템플릿 잠금 아래에서는 일어나지 않아야 하는 일이라, 잠금을 거치지 않은 경로를 찾을 수 있게 남긴다.
			log.warn("이미 같은 템플릿의 쿠폰이 있어 저장하지 못했습니다(UNIQUE 위반): userId={}, templateId={}",
				coupon.getUserId(), coupon.getTemplateId());
			throw new PaymentException("이미 발급받은 쿠폰입니다.", e);
		}
	}

	/**
	 * 내 쿠폰함. 아직 쓰지 않았고 지금 만료되지 않은 쿠폰이다. 기간 없는 쿠폰도 들어간다({@link Coupon#isExpired}).
	 */
	@Transactional(readOnly = true)
	public List<Coupon> getMyCoupons(Long userId) {
		return couponRepository.findAllAvailableByUserId(userId, LocalDateTime.now(clock));
	}

	// 결제 시 쿠폰 적용 및 검증
	@Transactional
	public DiscountValidationResult validateAndCalculateCoupon(Long couponId, Long userId,
		int originalAmount) {
		Coupon coupon = couponRepository.findByIdWithLock(couponId)
			.orElseThrow(() -> new PaymentException("존재하지 않는 쿠폰입니다."));

		if (!coupon.getUserId().equals(userId)) {
			throw new PaymentException("본인의 쿠폰만 사용할 수 있습니다.");
		}

		// 이미 쓴 쿠폰과 만료된 쿠폰은 금액을 계산하기 전에, 주문을 저장하기 전에 거른다.
		if (coupon.isUsed()) {
			throw new PaymentException("이미 사용한 쿠폰입니다.");
		}
		if (coupon.isExpired(LocalDateTime.now(clock))) {
			throw new PaymentException("기간이 만료된 쿠폰입니다.");
		}

		int finalAmount = coupon.applyDiscount(originalAmount);

		// 할인 코드 검증과 같은 결과 타입을 쓴다. 쿠폰은 할인 코드 엔티티가 없어 null 을 넣는다.
		return new DiscountValidationResult(finalAmount, coupon.getName(), null);
	}

	/**
	 * 주문을 만들 때 쿠폰을 사용 처리한다.
	 *
	 * <p>쿠폰 행을 스스로 잠가(SELECT ... FOR UPDATE) 읽는다. 같은 쿠폰으로 동시에 들어온 두 요청이 둘 다 미사용으로 읽고 둘 다
	 * 사용 처리하지 않게 하기 위해서다. 같은 트랜잭션에서 {@link #validateAndCalculateCoupon} 이 이미 잠갔다면 이미 쥔 잠금이라
	 * 더 기다리지 않는다. 잠금은 호출자의 트랜잭션이 끝날 때 풀린다.
	 */
	@Transactional
	public void useCoupon(Long couponId) {
		Coupon coupon = couponRepository.findByIdWithLock(couponId)
			.orElseThrow(() -> new PaymentException("쿠폰 없음"));
		coupon.use(LocalDateTime.now(clock));
	}

	/**
	 * 만료 뒤 늦게 결제된 주문 몫으로, 만료 때 돌려놓은 쿠폰을 다시 사용 처리한다.
	 *
	 * <p>쿠폰 행을 잠가 읽은 뒤 미사용이면 사용 처리한다. 그사이 다른 주문이 이 쿠폰을 이미 썼거나 쿠폰이 지워졌으면 아무것도
	 * 바꾸지 않고 false 를 돌려준다. 호출자는 그 결제를 확정하지 않고 취소한다. 예외로 끝내면 결제는 승인된 채 취소도 알림도 없이
	 * 남는다. 쿠폰 기간은 보지 않는다({@link Coupon#useForPaidOrder(LocalDateTime)}).
	 *
	 * <p>호출자(결제 확정)가 주문 행을 잠근 트랜잭션 안에서 부른다. 그래서 이 경로는 주문 행 → 쿠폰 행 순서로 잠그고, 쿠폰 행 → 주문
	 * INSERT 순서인 주문 생성과 반대다. orders.merchant_uid 인덱스가 없으면 둘이 교착될 수 있다(OrderDiscountRestorer 클래스 설명).
	 * 잠금은 호출자의 트랜잭션이 끝날 때 풀린다. 트랜잭션 밖에서 부르면 이 전제가 깨지므로 {@link Propagation#MANDATORY} 로 진행 중인
	 * 트랜잭션이 없으면 IllegalTransactionStateException 을 던진다.
	 *
	 * @return 이 호출로 사용 처리했으면 true, 다른 주문이 이미 쓰고 있거나 쿠폰이 없어 그대로 두었으면 false
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean claimForPaidOrder(Long couponId) {
		Optional<Coupon> found = couponRepository.findByIdWithLock(couponId);
		if (found.isEmpty() || found.get().isUsed()) {
			return false;
		}
		Coupon coupon = found.get();
		coupon.useForPaidOrder(LocalDateTime.now(clock));
		return true;
	}

	/**
	 * 지금 발급 기간인 쿠폰 이벤트 목록. 이벤트마다 이 사용자가 이미 받았는지, 선착순 마감인지, 지금 받으면 언제까지 쓸 수 있는지를
	 * 담는다.
	 */
	@Transactional(readOnly = true)
	public List<CouponEventDto> getCouponEvents(Long userId) {
		LocalDateTime now = LocalDateTime.now(clock);
		return couponTemplateRepository.findAllWithIssueStatus(userId, now).stream()
			.map(row -> toEventDto(row, now))
			.toList();
	}

	private CouponEventDto toEventDto(CouponEventRow row, LocalDateTime now) {
		CouponTemplate template = row.template();
		return new CouponEventDto(
			template.getId(),
			template.getName(),
			template.getDiscountType().toString(),
			template.getDiscountValue(),
			validPeriodText(template, now),
			row.issued(),
			template.isSoldOut()
		);
	}

	/**
	 * 지금(now) 받으면 언제까지 쓸 수 있는지 보여 줄 문구. 받을 쿠폰의 만료 시각과 같은 계산({@link CouponTemplate#couponExpiresAt})
	 * 으로 정한다. 예: "2026.12.31 까지". 기간 없는 쿠폰이면 "기간 제한 없음".
	 */
	private static String validPeriodText(CouponTemplate template, LocalDateTime now) {
		LocalDateTime expiresAt = template.couponExpiresAt(now);
		if (expiresAt == null) {
			return "기간 제한 없음";
		}
		return expiresAt.format(VALID_PERIOD_DATE_FORMAT) + " 까지";
	}

	/**
	 * 주문이 쿠폰을 놓을 때(만료·환불·웹훅 실패 기록) 쿠폰을 미사용으로 되돌린다.
	 *
	 * <p>쿠폰 행을 잠가(SELECT ... FOR UPDATE) 가장 최근에 커밋된 상태를 읽는다. 잠그지 않고 읽으면 트랜잭션 스냅샷의 값을 읽고,
	 * 그사이 다른 트랜잭션이 바꾼 쿠폰을 덮어쓴다.
	 *
	 * <p>호출자(OrderDiscountRestorer.restore)는 주문 행을 잠그고 주문 상태를 바꾼 트랜잭션 안에서, 이 쿠폰을 쥔 다른 주문이
	 * 없음을 확인한 뒤 부른다. 주문 상태 변경과 쿠폰 되돌리기가 함께 커밋·롤백되고 쿠폰 행 잠금이 그 트랜잭션 끝까지 남도록
	 * {@link Propagation#MANDATORY} 로 진행 중인 트랜잭션이 없으면 IllegalTransactionStateException 을 던진다.
	 *
	 * @throws PaymentException 쿠폰이 없을 때
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void restoreCoupon(Long couponId) {
		if (couponId == null) {
			return;
		}

		Coupon coupon = couponRepository.findByIdWithLock(couponId)
			.orElseThrow(() -> new PaymentException("쿠폰 정보를 찾을 수 없습니다."));

		// 사용된 상태라면 복구
		if (coupon.isUsed()) {
			coupon.restore();
		}
	}
}
