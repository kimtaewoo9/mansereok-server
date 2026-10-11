package com.mansereok.server.domain.coupon.repository;

import com.mansereok.server.domain.coupon.entity.CouponTemplate;

/**
 * 쿠폰 이벤트 목록 조회({@link CouponTemplateRepository#findAllWithIssueStatus})의 한 행. 발급 기간인 템플릿과, 조회한 사용자가
 * 그 템플릿의 쿠폰을 이미 받았는지(issued)를 담는다.
 */
public record CouponEventRow(CouponTemplate template, boolean issued) {

}
