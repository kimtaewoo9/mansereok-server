package com.mansereok.server.domain.payment.reconciliation.repository;

import com.mansereok.server.domain.payment.reconciliation.entity.PaymentReconciliationRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentReconciliationRunRepository
	extends JpaRepository<PaymentReconciliationRun, Long> {

}
