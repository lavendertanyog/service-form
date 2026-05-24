package com.example.demo;

import jakarta.persistence.*;

@Entity
@Table(name = "customers")
public class Customer {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String clientName;
    
    private String clientOrganisation;
    private String clientEmails;

    // Constructors
    public Customer() {}
    
    public Customer(String clientName, String clientOrganisation, String clientEmails) {
        this.clientName = clientName;
        this.clientOrganisation = clientOrganisation;
        this.clientEmails = clientEmails;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public String getClientName() { return clientName; }
    public void setClientName(String clientName) { this.clientName = clientName; }
    public String getClientOrganisation() { return clientOrganisation; }
    public void setClientOrganisation(String clientOrganisation) { this.clientOrganisation = clientOrganisation; }
    public String getClientEmails() { return clientEmails; }
    public void setClientEmails(String clientEmails) { this.clientEmails = clientEmails; }
}
