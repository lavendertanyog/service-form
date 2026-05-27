package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.sendgrid.*;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.*;

@RestController
@CrossOrigin(origins = "*")
public class ServiceFormController {

    @Value("${SENDGRID_API_KEY}")
    private String sendGridApiKey;

    @Value("${ADMIN_SECRET_TOKEN:mydefaultsecrettoken}")
    private String adminSecretToken;

    @Value("${app.finance-emails:rebecca.goh@nextan.com.sg}")
    private String financeEmails;

    @Autowired(required = false)
    private CompanyRepository companyRepository; 

    @Autowired(required = false)
    private CustomerRepository customerRepository;

    @GetMapping("/config")
    public String getConfig() {
        String staffOptionsJson = "[\"john.tan@nextan.com.sg\", \"junan.yong@nextan.com.sg\"]";
        String companiesJson = "[]";
        if (companyRepository != null) {
            try {
                ObjectMapper mapper = new ObjectMapper();
                companiesJson = mapper.writeValueAsString(companyRepository.findAll());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        
        return "{\n" +
               "  \"staffOptions\": " + staffOptionsJson + ",\n" +
               "  \"companiesDatabase\": " + companiesJson + "\n" +
               "}";
    }

    @PostMapping("/submit")
    public String handleSubmit(
            @RequestParam("generatedRef") String referenceNumber, 
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
            // Database Layer Auto-Save
            if (companyRepository != null && customerRepository != null) {
                String cleanCompanyName = clientOrganisation.trim();
                String cleanCustomerName = clientName.trim();
                String cleanCustomerEmail = clientEmails.trim();

                List<Company> existingCompanies = companyRepository.findAll();
                Company targetCompany = null;
                for (Company comp : existingCompanies) {
                    if (comp.getCompanyName().equalsIgnoreCase(cleanCompanyName)) {
                        targetCompany = comp;
                        break;
                    }
                }
                if (targetCompany == null) {
                    targetCompany = new Company(cleanCompanyName);
                    targetCompany = companyRepository.save(targetCompany);
                }
                boolean customerExists = false;
                if (targetCompany.getCustomers() != null) {
                    for (Customer cust : targetCompany.getCustomers()) {
                        if (cust.getClientEmails().equalsIgnoreCase(cleanCustomerEmail)) {
                            customerExists = true;
                            break;
                        }
                    }
                }
                if (!customerExists) {
                    Customer newCustomer = new Customer(cleanCustomerName, cleanCustomerEmail, targetCompany);
                    customerRepository.save(newCustomer);
                }
            }

            // Parse Technicians Names
            List<String> technicianNames = new ArrayList<>();
            List<String> recipientsList = new ArrayList<>();
            recipientsList.add(clientEmails.trim());

            if (staffEmails != null && !staffEmails.trim().isEmpty()) {
                for (String email : staffEmails.split(",")) {
                    String cleanEmail = email.trim();
                    recipientsList.add(cleanEmail);
                    if (cleanEmail.contains("@")) {
                        String handle = cleanEmail.split("@")[0];
                        if (!handle.isEmpty()) {
                            technicianNames.add(handle.substring(0, 1).toUpperCase() + handle.substring(1));
                        }
                    } else {
                        technicianNames.add(cleanEmail);
                    }
                }
            }
            String displayTechnicians = technicianNames.isEmpty() ? "Not Assigned" : String.join(", ", technicianNames);

            if ("true".equalsIgnoreCase(invoiceable) && financeEmails != null && !financeEmails.trim().isEmpty()) {
                for (String finEmail : financeEmails.split(",")) {
                    String cleanFinEmail = finEmail.trim();
                    if (!cleanFinEmail.isEmpty()) {
                        recipientsList.add(cleanFinEmail);
                    }
                }
            }

            // Clean up base64 payload signatures
            String cleanSignatureData = signatureBase64.contains(",") ? signatureBase64.split(",")[1] : signatureBase64;
            
            // Build the identical UI replica HTML Template for PDF Engine
            String pdfHtmlTemplate = "<!DOCTYPE html><html><head><style>" +
                    "body { margin: 0; padding: 20px; background-color: #f5f7fb; color: #273142; font-family: 'Arial', sans-serif; }" +
                    ".container { width: 100%; max-width: 900px; margin: 0 auto; background: #ffffff; border-radius: 20px; border: 1px solid rgba(16, 24, 40, 0.08); overflow: hidden; }" +
                    ".header { padding: 20px 24px; background: #ffffff; border-bottom: 1px solid rgba(16, 24, 40, 0.08); display: block; height: 40px; }" +
                    ".ref-display { float: left; font-size: 0.85rem; color: #6c7284; background: #f0f4f9; padding: 6px 12px; border-radius: 8px; border: 1px solid #d6d9e6; font-family: monospace; font-weight: 600; }" +
                    ".logo { float: left; margin-left: 40px; font-weight: 700; font-size: 1.3rem; color: #000000; line-height: 32px; }" +
                    ".header-logo-img { float: right; height: 35px; width: auto; }" +
                    ".clear { clear: both; }" +
                    ".form-panel { padding: 24px 20px; }" +
                    ".row { width: 100%; display: block; clear: both; margin-bottom: 15px; }" +
                    ".half { width: 48%; float: left; margin-right: 2%; }" +
                    ".half-last { width: 48%; float: left; }" +
                    ".full { width: 100%; float: left; }" +
                    ".field { display: block; margin-bottom: 5px; }" +
                    ".field label { font-size: 0.90rem; color: #273142; font-weight: 600; display: block; margin-bottom: 8px; text-transform: none; }" +
                    ".mock-input { width: 100%; border: 1px solid #d6d9e6; border-radius: 12px; padding: 12px 14px; font-size: 0.95rem; color: #273142; background: #ffffff; min-height: 20px; box-sizing: border-box; }" +
                    ".mock-textarea { min-height: 90px; }" +
                    ".radio-group { display: block; padding: 12px 16px; border: 1px solid #d6d9e6; border-radius: 14px; background: #ffffff; }" +
                    ".radio-item { font-size: 0.95rem; font-weight: 700; color: #1f7efd; }" +
                    ".signature-box { border: 1px solid #d6d9e6; border-radius: 16px; background: #ffffff; text-align: center; padding: 10px; }" +
                    "</style></head><body>" +
                    "<div class=\"container\">" +
                    "  <div class=\"header\">" +
                    "    <div class=\"ref-display\">" + referenceNumber + "</div>" +
                    "    <div class=\"logo\">Nextan Service Form</div>" +
                    "    <img src=\"https://cdn.techinasia.com/data/images/l87azogSobIoUGTU096RrJZB7gn0vHTdlWthFC0u.jpeg\" class=\"header-logo-img\" />" +
                    "  </div>" +
                    "  <div class=\"clear\"></div>" +
                    "  <div class=\"form-panel\">" +
                    "    <div class=\"row\">" +
                    "      <div class=\"full\"><div class=\"field\"><label>Assigned Engineer Email Address(es)</label><div class=\"mock-input\">" + staffEmails + "</div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\">" +
                    "      <div class=\"half\"><div class=\"field\"><label>Job Site / Building Name</label><div class=\"mock-input\">" + jobSite + "</div></div></div>" +
                    "      <div class=\"half-last\"><div class=\"field\"><label>Location / Unit Number</label><div class=\"mock-input\">" + location + "</div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\">" +
                    "      <div class=\"half\"><div class=\"field\"><label>Date of Service</label><div class=\"mock-input\">" + serviceDate + "</div></div></div>" +
                    "      <div class=\"half-last\"><div class=\"field\"><label>Time of Service</label><div class=\"mock-input\">" + serviceTime + "</div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\">" +
                    "      <div class=\"full\"><div class=\"field\"><label>Service Request / Fault Reported</label><div class=\"mock-input\">" + serviceRequest + "</div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\">" +
                    "      <div class=\"full\"><div class=\"field\"><label>Service Details / Action Taken</label><div class=\"mock-input mock-textarea\">" + serviceDetails.replaceAll("\n", "<br/>") + "</div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\">" +
                    "      <div class=\"half\"><div class=\"field\"><label>Client Organisation</label><div class=\"mock-input\">" + clientOrganisation + "</div></div></div>" +
                    "      <div class=\"half-last\"><div class=\"field\"><label>Client Representative Name</label><div class=\"mock-input\">" + clientName + "</div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\">" +
                    "      <div class=\"full\"><div class=\"field\"><label>Client Representative Email(s)</label><div class=\"mock-input\">" + clientEmails + "</div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\">" +
                    "      <div class=\"full\"><div class=\"field\"><label>Is this service billable / invoiceable?</label><div class=\"radio-group\"><span class=\"radio-item\">" + ("true".equalsIgnoreCase(invoiceable) ? "✓ Yes, Invoice Required" : "No, Under Warranty / Contract Maintenance") + "</span></div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\" style=\"margin-top:20px;\">" +
                    "      <div class=\"full\"><div class=\"field\"><label>Customer Signature</label><div class=\"signature-box\"><img src=\"data:image/png;base64," + cleanSignatureData + "\" style=\"width:320px; height:130px; object-fit:contain;\" /></div></div></div>" +
                    "    </div>" +
                    "    <div class=\"clear\"></div>" +
                    "  </div>" +
                    "</div>" +
                    "</body></html>";

            ByteArrayOutputStream os = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode(); 
            builder.withHtmlContent(pdfHtmlTemplate, "/");
            builder.toStream(os);
            builder.run();
            byte[] pdfBytes = os.toByteArray();

            // Construct HTTP API Mail Request Object with reference number tagged in subject
            Email from = new Email("eunicetanyongnie@gmail.com"); 
            String subject = "[" + referenceNumber + "] Nextan Service Form for " + clientName;
            
            String emailBodyText = String.format(
                "Dear %s from %s,<br/><br/>" +
                "Please find attached a copy of the Service Sheet for the Service provided today at %s.<br/><br/>" +
                "If you have any questions, concerns, or disagreements regarding the contents, we kindly request that you reach out to us within the next <b><u>three</u></b> working days.<br/><br/>" +
                "If we do not receive any communication from you within this designated time frame, we will consider the service sheet as accurate and satisfactory.<br/><br/>" +
                "Rest assured, we remain dedicated to resolving any potential concerns you may have, even after this period.<br/><br/><br/>" +
                "Best,<br/>" +
                "Nextan Service Team.<br/><br/>" +
                "67 Ayer Rajah Crescent #04-21<br/>" +
                "+65 6872 6423",
                clientName, clientOrganisation, jobSite
            );
            
            Content content = new Content("text/html", emailBodyText);

            Personalization personalization = new Personalization();
            for (String recipientEmail : recipientsList) {
                personalization.addTo(new Email(recipientEmail));
            }

            Mail mail = new Mail();
            mail.setFrom(from);
            mail.setSubject(subject);
            mail.addContent(content);
            mail.addPersonalization(personalization);

            // Attach Generated Summary PDF via Base64 (using reference number in filename)
            String safeFileName = "Nextan_Service_Form_" + referenceNumber + ".pdf";
            Attachments pdfAttachment = new Attachments();
            pdfAttachment.setContent(Base64.getEncoder().encodeToString(pdfBytes));
            pdfAttachment.setType("application/pdf");
            pdfAttachment.setFilename(safeFileName);
            pdfAttachment.setDisposition("attachment");
            mail.addAttachments(pdfAttachment);

            if (attachments != null) {
                for (MultipartFile file : attachments) {
                    if (!file.isEmpty()) {
                        Attachments customFile = new Attachments();
                        customFile.setContent(Base64.getEncoder().encodeToString(file.getBytes()));
                        customFile.setType(file.getContentType());
                        customFile.setFilename(file.getOriginalFilename());
                        customFile.setDisposition("attachment");
                        mail.addAttachments(customFile);
                    }
                }
            }

            SendGrid sg = new SendGrid(sendGridApiKey);
            Request request = new Request();
            request.setMethod(Method.POST);
            request.setEndpoint("mail/send");
            request.setBody(mail.build());
            
            Response response = sg.api(request);
            
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300) {
                return "{\"success\": true}";
            } else {
                return "{\"success\": false, \"error\": \"SendGrid API Error: " + response.getBody() + "\"}";
            }

        } catch (Exception e) {
            e.printStackTrace();
            return "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}";
        }
    }
    
    @PostMapping("/admin/add-company")
    public String addCompany(@RequestHeader(value = "X-Admin-Token", required = false) String providedToken, @RequestParam("companyName") String companyName) {
        if (providedToken == null || !providedToken.equals(adminSecretToken)) return "{\"success\": false, \"error\": \"Unauthorized\"}";
        try {
            if (companyRepository == null) return "{\"success\": false, \"error\": \"Missing Repository\"}";
            Company company = new Company(companyName.trim());
            companyRepository.save(company);
            return "{\"success\": true, \"message\": \"Added company: \" + companyName}";
        } catch (Exception e) { return "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}"; }
    }

    @PostMapping("/admin/add-customer")
    public String addCustomer(@RequestHeader(value = "X-Admin-Token", required = false) String providedToken, @RequestParam("customerName") String customerName, @RequestParam("customerEmail") String customerEmail, @RequestParam("companyId") Long companyId) {
        if (providedToken == null || !providedToken.equals(adminSecretToken)) return "{\"success\": false, \"error\": \"Unauthorized\"}";
        try {
            if (companyRepository == null || customerRepository == null) return "{\"success\": false, \"error\": \"Repositories missing\"}";
            Optional<Company> companyOptional = companyRepository.findById(companyId);
            if (!companyOptional.isPresent()) return "{\"success\": false, \"error\": \"Company not found\"}";
            Company parentCompany = companyOptional.get();
            Customer customer = new Customer(customerName.trim(), customerEmail.trim(), parentCompany);
            customerRepository.save(customer);
            return "{\"success\": true, \"message\": \"Added customer \" + customerName}";
        } catch (Exception e) { return "{\"success\": false, \"error\": \"" + e.getMessage() + "\"}"; }
    }
}