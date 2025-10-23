package com.mansereok.server.repository;

import com.mansereok.server.entity.SubCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SubCategoryRepository extends JpaRepository<SubCategory, Long> {

	List<SubCategory> findAllByCategoryId(Long categoryId);
}
