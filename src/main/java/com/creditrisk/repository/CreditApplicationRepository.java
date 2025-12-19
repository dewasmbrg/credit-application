package com.creditrisk.repository;

import com.creditrisk.model.CreditApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface CreditApplicationRepository extends JpaRepository<CreditApplication, String> {

    Optional<CreditApplication> findByApplicationId(String applicationId);
}
