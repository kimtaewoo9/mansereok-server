package com.mansereok.server.domain.product.repository;

import com.mansereok.server.domain.product.entity.SubCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubCategoryRepository extends JpaRepository<SubCategory, Long> {

	List<SubCategory> findAllByCategoryId(Long categoryId);
}
