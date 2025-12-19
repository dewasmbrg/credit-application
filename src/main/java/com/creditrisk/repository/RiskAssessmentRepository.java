package com.creditrisk.repository;

import com.creditrisk.model.RiskAssessment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RiskAssessmentRepository extends JpaRepository<RiskAssessment, String> {

    Optional<RiskAssessment> findByApplicationId(String applicationId);
}
