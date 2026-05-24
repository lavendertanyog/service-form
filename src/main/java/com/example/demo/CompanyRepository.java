package com.example.demo;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface CompanyRepository extends JpaRepository<Company, Long> {
    /**
     * Looks up a company profile by its name, ignoring case sensitivity.
     * Used by the controller to find existing companies before creating new entries.
     */
    Optional<Company> findByCompanyNameIgnoreCase(String companyName);
}
