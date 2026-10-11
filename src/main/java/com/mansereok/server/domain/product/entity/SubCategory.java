package com.mansereok.server.domain.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

// 상품 목록(findAllByCategoryId)이 쓰는 인덱스. 운영은 ddl-auto: validate 라 schema.sql 과 같은 이름으로 손으로 적용한다.
@Table(name = "subcategories",
	indexes = @Index(name = "idx_subcategories_category_id", columnList = "category_id"))
@Entity
@Getter
@ToString
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubCategory {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;
	private String title;
	private String subtitle;
	private String description;
	private Integer price;
	@Column(name = "original_price") // 추가된 컬럼 매핑
	private Integer originalPrice;
	private String icon;

	@Column(name = "category_id")
	private Long categoryId;
}
