package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

@RestController
@CrossOrigin(origins = "*")
public class ServiceFormController {

    @Autowired
    private JavaMailSender mailSender;

    @Value("${ADMIN_SECRET_TOKEN:mydefaultsecrettoken}")
    private String adminSecretToken;

    // Direct interface links matching your repositories
    @Autowired(required = false)
    private CompanyRepository companyRepository; 

    @Autowired(required = false)
    private CustomerRepository customerRepository;

    // ==========================================
    // BACKDOOR 1: ADD NEW COMPANY TO DATABASE
    // ==========================================
    @PostMapping("/admin/add-company")
    public String addCompany(
            @RequestHeader(value = "X-Admin-Token", required = false) String providedToken,
            @RequestParam("companyName") String companyName) {
        
        if (providedToken == null || !providedToken.equals(adminSecretToken)) {
            return "{\"success\": false, \"error\": \"Unauthorized: Invalid Admin Token\"}";
        }

        try {
            if (companyRepository == null) {
                return "{\"success\": false, \"error\": \"CompanyRepository bean missing from project setup.\"}";
            }
            
            Company company = new Company(companyName.trim());
            companyRepository.save(company);
            
            return "{\"success\": true, \"message\": \"Successfully added company: " + companyName + "\"}";
        } catch (Exception e) {
            return "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ==========================================
    // BACKDOOR 2: ADD NEW CUSTOMER TO DATABASE
    // ==========================================
    @PostMapping("/admin/add-customer")
    public String addCustomer(
            @RequestHeader(value = "X-Admin-Token", required = false) String providedToken,
            @RequestParam("customerName") String customerName,
            @RequestParam("customerEmail") String customerEmail,
            @RequestParam("companyId") Long companyId) {
        
        if (providedToken == null || !providedToken.equals(adminSecretToken)) {
            return "{\"success\": false, \"error\": \"Unauthorized: Invalid Admin Token\"}";
        }

        try {
            if (companyRepository == null || customerRepository == null) {
                return "{\"success\": false, \"error\": \"Database repositories are not initialized.\"}";
            }
            
            // 1. Look up the existing company object from the database by its numerical ID
            Optional<Company> companyOptional = companyRepository.findById(companyId);
            if (!companyOptional.isPresent()) {
                return "{\"success\": false, \"error\": \"Company with ID " + companyId + " not found inside the database.\"}";
            }
            
            Company parentCompany = companyOptional.get();

            // 2. Build the customer object using the proper Entity Constructor rules
            Customer customer = new Customer(customerName.trim(), customerEmail.trim(), parentCompany);
            customerRepository.save(customer);
            
            return "{\"success\": true, \"message\": \"Successfully added customer " + customerName + " under " + parentCompany.getCompanyName() + "\"}";
        } catch (Exception e) {
            return "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}";
        }
    }

    // ==========================================
    // CORE CUSTOMER SERVICE SHEET FORM SUBMIT
    // ==========================================
    @PostMapping("/submit")
    public String handleSubmit(
            @RequestParam("jobSite") String jobSite,
            @RequestParam("location") String location,
            @RequestParam("serviceDate") String serviceDate,
            @RequestParam("serviceTime") String serviceTime,
            @RequestParam("serviceRequest") String serviceRequest,
            @RequestParam("serviceDetails") String serviceDetails,
            @RequestParam("clientOrganisation") String clientOrganisation,
            @RequestParam("clientName") String clientName,
            @RequestParam("clientEmails") String clientEmails,
            @RequestParam(value = "staffEmails", required = false) String staffEmails,
            @RequestParam("invoiceable") String invoiceable,
            @RequestParam("signature") String signatureBase64,
            @RequestParam(value = "attachments", required = false) MultipartFile[] attachments) {

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            List<String> recipients = new ArrayList<>();
            recipients.add(clientEmails.trim());

            if (staffEmails != null && !staffEmails.trim().isEmpty()) {
                for (String email : staffEmails.split(",")) {
                    recipients.add(email.trim());
                }
            }

            if ("true".equalsIgnoreCase(invoiceable)) {
                recipients.add("rebecca.goh@nextan.com.sg");
            }

            helper.setTo(recipients.toArray(new String[0]));
            helper.setSubject("Nextan Service Form for " + clientName);

            String emailBody = String.format(
                "Dear %s from %s,\n\n" +
                "Please find attached a copy of the Service Sheet for the Service provided today at %s.\n\n" +
                "If you have any questions, concerns, or disagreements regarding the contents, we kindly request that you reach out to us within the next three working days.\n\n" +
                "If we do not receive any communication from you within this designated time frame, we will consider the service sheet as accurate and satisfactory.\n\n" +
                "Rest assured, we remain dedicated to resolving any potential concerns you may have, even after this period.\n\n\n" +
                "Best,\n" +
                "Nextan Service Team.\n" +
                "67 Ayer Rajah Crescent #04-21\n" +
                "+65 6872 6423",
                clientName, clientOrganisation, jobSite
            );
            helper.setText(emailBody);

            String cleanSignatureData = signatureBase64;
            if (cleanSignatureData.contains(",")) {
                cleanSignatureData = cleanSignatureData.split(",")[1];
            }

            String pdfHtmlTemplate = "<!DOCTYPE html><html><head><style>" +
                    "body { font-family: 'Arial', sans-serif; color: #273142; padding: 30px; }" +
                    ".header { border-bottom: 2px solid #1f7efd; padding-bottom: 15px; margin-bottom: 30px; }" +
                    ".title { font-size: 24px; font-weight: bold; color: #0f172a; }" +
                    ".invoice-tag { background: #d63335; color: white; padding: 4px 12px; font-size: 12px; float: right; font-weight: bold; }" +
                    ".field-box { background: #f8fafc; border: 1px solid #e2e8f0; padding: 12px; margin-bottom: 15px; border-radius: 6px; }" +
                    ".label { font-size: 11px; font-weight: bold; color: #6c7284; text-transform: uppercase; margin-bottom: 5px; }" +
                    ".val { font-size: 14px; }" +
                    ".signature-box { margin-top: 30px; border: 1px solid #d6d9e6; padding: 15px; width: 350px; }" +
                    "</style></head><body>" +
                    "<div class=\"header\">" +
                    ("true".equalsIgnoreCase(invoiceable) ? "<div class=\"invoice-tag\">INVOICEABLE SERVICE</div>" : "") +
                    "<div class=\"title\"><span style=\"color:#1f7efd;\">nextan</span> Service Form Summary</div>" +
                    "</div>" +
                    "<div class=\"field-box\"><div class=\"label\">Job Site / Location</div><div class=\"val\">" + jobSite + " (" + location + ")</div></div>" +
                    "<div class=\"field-box\"><div class=\"label\">Date &amp; Time of Service</div><div class=\"val\">" + serviceDate + " at " + serviceTime + "</div></div>" +
                    "<div class=\"field-box\"><div class=\"label\">Company Name</div><div class=\"val\">" + clientOrganisation + "</div></div>" +
                    "<div class=\"field-box\"><div class=\"label\">Customer Name</div><div class=\"val\">" + clientName + "</div></div>" +
                    "<div class=\"field-box\"><div class=\"label\">Service Request</div><div class=\"val\">" + serviceRequest + "</div></div>" +
                    "<div class=\"field-box\"><div class=\"label\">Service Details</div><div class=\"val\" style=\"white-space: pre-wrap;\">" + serviceDetails + "</div></div>" +
                    "<div class=\"field-box\"><div class=\"label\">Assigned Engineers</div><div class=\"val\">" + (staffEmails != null ? staffEmails : "") + "</div></div>" +
                    "<div class=\"signature-box\"><div class=\"label\">Customer Signature</div>" +
                    "<img src=\"data:image/png;base64," + cleanSignatureData + "\" style=\"width:300px; height:120px;\" />" +
                    "</div>" +
                    "</body></html>";

            ByteArrayOutputStream os = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            
            builder.useFastMode(); 
            builder.withHtmlContent(pdfHtmlTemplate, "/");
            builder.toStream(os);
            builder.run();
            byte[] pdfBytes = os.toByteArray();

            String safeFileName = "Nextan_Service_Form_" + clientName.replaceAll("\\s+", "_") + ".pdf";
            ByteArrayDataSource pdfDataSource = new ByteArrayDataSource(pdfBytes, "application/pdf");
            helper.addAttachment(safeFileName, pdfDataSource);

            if (attachments != null) {
                for (MultipartFile file : attachments) {
                    if (!file.isEmpty()) {
                        helper.addAttachment(file.getOriginalFilename(), file);
                    }
                }
            }

            mailSender.send(message);
            return "{\"success\": true}";

        } catch (Exception e) {
            e.printStackTrace();
            return "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}";
        }
    }
}