package com.example.demo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity
@Table(name = "customers")
public class Customer {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String clientName;
    
    private String clientEmails;

    @ManyToOne
    @JoinColumn(name = "company_id", nullable = false)
    @JsonIgnore // Prevents infinite loops when converting database data to JSON
    private Company company;

    public Customer() {}
    
    public Customer(String clientName, String clientEmails, Company company) {
        this.clientName = clientName;
        this.clientEmails = clientEmails;
        this.company = company;
    }

    public Long getId() { return id; }
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }
    public String getClientEmails() { return clientEmails; }
    public void setClientEmails(String clientEmails) { this.clientEmails = clientEmails; }
    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }
}
