package com.mansereok.server.service.response;

import com.mansereok.server.entity.ProductStatus;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InterpretationPageResponse {

	private String productName;
	private LocalDate createdAt;
	private ProductStatus productStatus;
}
