package com.mansereok.server.domain.interpret.dto.response;


import com.mansereok.server.domain.interpret.entity.ResultStatus;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompatibilityPageResponse {

	private String productName;
	private LocalDate createdAt;
	private ResultStatus productStatus;
	private Long paymentId;
}
