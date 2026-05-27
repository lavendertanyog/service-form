package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.sendgrid.*;
import com.sendgrid.helpers.mail.Mail;
import com.sendgrid.helpers.mail.objects.*;

// Cloudflare R2 / AWS S3 compatibility SDK imports
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectCommand;

@RestController
@CrossOrigin(origins = "*")
public class ServiceFormController {

    @Value("${SENDGRID_API_KEY}")
    private String sendGridApiKey;

    @Value("${ADMIN_SECRET_TOKEN:mydefaultsecrettoken}")
    private String adminSecretToken;

    @Value("${app.finance-emails:rebecca.goh@nextan.com.sg}")
    private String financeEmails;

    // Cloudflare R2 Connection Tokens (Add these to your environment variables on Railway)
    @Value("${R2_S3_ENDPOINT}")
    private String r2S3Endpoint;

    @Value("${R2_ACCESS_KEY_ID}")
    private String r2AccessKeyId;

    @Value("${R2_SECRET_ACCESS_KEY}")
    private String r2SecretAccessKey;

    @Value("${R2_PUBLIC_DEV_URL}")
    private String r2PublicDevUrl;

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
            List<String> recipientsList = new ArrayList<>();
            
            String[] splitCustomerEmails = clientEmails.split(",");
            for (String custEmail : splitCustomerEmails) {
                String cleanEmail = custEmail.trim();
                if (!cleanEmail.isEmpty()) {
                    recipientsList.add(cleanEmail);
                }
            }

            if (companyRepository != null && customerRepository != null) {
                String cleanCompanyName = clientOrganisation.trim();
                String cleanCustomerName = clientName.trim();

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
                
                for (String clientEmailElement : recipientsList) {
                    boolean customerExists = false;
                    if (targetCompany.getCustomers() != null) {
                        for (Customer cust : targetCompany.getCustomers()) {
                            if (cust.getClientEmails().equalsIgnoreCase(clientEmailElement)) {
                                customerExists = true;
                                break;
                            }
                        }
                    }
                    if (!customerExists) {
                        Customer newCustomer = new Customer(cleanCustomerName, clientEmailElement, targetCompany);
                        customerRepository.save(newCustomer);
                    }
                }
            }

            List<String> technicianNames = new ArrayList<>();
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

            String cleanSignatureData = signatureBase64.contains(",") ? signatureBase64.split(",")[1] : signatureBase64;
            
            String digitsOnly = referenceNumber != null ? referenceNumber.replaceAll("[^0-9]", "") : "";
            String formattedRef = String.format("%10s", digitsOnly).replace(' ', '0');

            String senderEmailString = "eunicetanyongnie@gmail.com";
            Email from = new Email(senderEmailString); 
            
            String subject = "Nextan Service Form for " + clientName + " REF-" + formattedRef;
            
            // Build the Zip stream container
            String zipLinkHtml = "";
            boolean hasValidFiles = false;
            ByteArrayOutputStream zipByteStream = new ByteArrayOutputStream();
            String zipFileName = "Attachments_" + formattedRef + ".zip";
            
            if (attachments != null && attachments.length > 0) {
                try (ZipOutputStream zos = new ZipOutputStream(zipByteStream)) {
                    for (MultipartFile file : attachments) {
                        if (file != null && !file.isEmpty()) {
                            hasValidFiles = true;
                            ZipEntry entry = new ZipEntry(file.getOriginalFilename());
                            zos.putNextEntry(entry);
                            zos.write(file.getBytes());
                            zos.closeEntry();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            // Cloudflare Upload Engine Routine
            if (hasValidFiles) {
                try {
                    // Create connection client instance
                    S3Client s3Client = S3Client.builder()
                            .endpointOverride(URI.create(r2S3Endpoint))
                            .credentialsProvider(StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create(r2AccessKeyId, r2SecretAccessKey)
                            ))
                            .region(Region.US_EAST_1)
                            .build();

                    String bucketName = "nextan-service-form-attachments";
                    byte[] zipBytes = zipByteStream.toByteArray();

                    // Push binary payload straight up to your bucket cloud system
                    s3Client.putObject(
                            builder -> builder.bucket(bucketName).key(zipFileName).contentType("application/zip"),
                            RequestBody.fromBytes(zipBytes)
                    );

                    // Generate clean secure public link format string layout matching your request instructions
                    String cleanFileDownloadUrl = r2PublicDevUrl + "/" + zipFileName;
                    zipLinkHtml = String.format(
                        "<a href=\"%s\" style=\"color: #2563eb; font-weight: bold; text-decoration: underline;\">%s</a>", 
                        cleanFileDownloadUrl, zipFileName
                    );

                } catch (Exception e) {
                    System.err.println("Cloudflare Upload Failed: " + e.getMessage());
                    e.printStackTrace();
                    // Fallback to text indicator if file fail occurs
                    zipLinkHtml = "[Attachment Upload Failed - Check Server System Logs]";
                }
            }

            String pdfHtmlTemplate = "<!DOCTYPE html><html><head><style>" +
                    "body { margin: 0; padding: 30px; background-color: #ffffff; color: #1e293b; font-family: 'Helvetica Neue', 'Arial', sans-serif; }" +
                    ".container { width: 100%; max-width: 950px; margin: 0 auto; background: #ffffff; }" +
                    ".pdf-header-table { width: 100%; border-collapse: collapse; margin-bottom: 25px; }" +
                    ".ref-badge { font-size: 0.85rem; color: #64748b; background: #f1f5f9; padding: 6px 12px; border-radius: 6px; border: 1px solid #e2e8f0; font-family: monospace; font-weight: 700; display: inline-block; }" +
                    ".main-title { font-size: 1.6rem; font-weight: 700; color: #0f172a; margin: 0; padding: 0; }" +
                    ".logo-text { font-size: 1.8rem; font-weight: 700; color: #57534e; letter-spacing: -1px; margin: 0; line-height: 1; }" +
                    ".logo-accent { color: #1e40af; }" +
                    ".logo-tagline { font-size: 0.85rem; color: #78716c; font-weight: 400; margin: 2px 0 0 0; font-family: 'Arial', sans-serif; }" +
                    ".clear { clear: both; }" +
                    ".form-body { padding: 10px 5px; }" +
                    ".row { width: 100%; display: block; clear: both; margin-bottom: 18px; }" +
                    ".col-6 { width: 48.5%; float: left; }" +
                    ".col-6-last { width: 48.5%; float: right; }" +
                    ".col-12 { width: 100%; float: left; }" +
                    ".field-group { display: block; }" +
                    ".field-group label { font-size: 0.95rem; color: #0f172a; font-weight: 600; display: block; margin-bottom: 6px; }" +
                    ".field-group label span { color: #dc2626; font-weight: bold; margin-left: 2px; }" +
                    ".input-mock { width: 100%; border: 1px solid #cbd5e1; border-radius: 10px; padding: 13px 14px; font-size: 0.95rem; color: #334155; background: #ffffff; min-height: 22px; box-sizing: border-box; }" +
                    ".textarea-mock { min-height: 110px; line-height: 1.5; }" +
                    ".radio-container { border: 1px solid #cbd5e1; border-radius: 10px; padding: 13px 16px; background: #ffffff; }" +
                    ".radio-option { font-size: 0.95rem; font-weight: 600; color: #2563eb; }" +
                    ".signature-frame { border: 1px solid #cbd5e1; border-radius: 12px; background: #ffffff; text-align: left; padding: 15px; min-height: 130px; page-break-inside: avoid !important; break-inside: avoid !important; }" +
                    ".upload-box { border: 1px solid #cbd5e1; border-radius: 10px; padding: 14px; background: #ffffff; min-height: 40px; }" +
                    ".file-link-item { font-size: 0.95rem; color: #2563eb; font-weight: 600; text-decoration: underline; margin-bottom: 4px; display: block; }" +
                    ".no-files-text { font-size: 0.95rem; color: #64748b; font-style: italic; }" +
                    "</style></head><body>" +
                    "<div class=\"container\">" +
                    "  <table class=\"pdf-header-table\">" +
                    "    <tr>" +
                    "      <td style=\"vertical-align: middle; text-align: left; width: 20%;\">" +
                    "        <div class=\"ref-badge\">" + referenceNumber + "</div>" +
                    "      </td>" +
                    "      <td style=\"vertical-align: middle; text-align: center; width: 50%;\">" +
                    "        <h1 class=\"main-title\">Nextan Service Form</h1>" +
                    "      </td>" +
                    "      <td style=\"vertical-align: middle; text-align: right; width: 30%;\">" +
                    "        <div class=\"logo-text\">next<span class=\"logo-accent\">a</span>n</div>" +
                    "        <div class=\"logo-tagline\">Innovative Technology Solutions</div>" +
                    "      </td>" +
                    "    </tr>" +
                    "  </table>" +
                    "  <hr style=\"border: 0; border-top: 1px solid #f1f5f9; margin-bottom: 20px;\" />" +
                    "  <div class=\"form-body\">" +
                    "    <div class=\"row\">" +
                    "      <div class=\"col-6\"><div class=\"field-group\"><label>Job site<span>*</span></label><div class=\"input-mock\">" + jobSite + "</div></div></div>" +
                    "      <div class=\"col-6-last\"><div class=\"field-group\"><label>Location<span>*</span></label><div class=\"input-mock\">" + location + "</div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\">" +
                    "      <div class=\"col-6\"><div class=\"field-group\"><label>Date of Service<span>*</span></label><div class=\"input-mock\">" + serviceDate + "</div></div></div>" +
                    "      <div class=\"col-6-last\"><div class=\"field-group\"><label>Time of Service<span>*</span></label><div class=\"input-mock\">" + serviceTime + "</div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\">" +
                    "      <div class=\"col-12\"><div class=\"field-group\"><label>Service Request<span>*</span></label><div class=\"input-mock\">" + serviceRequest + "</div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\">" +
                    "      <div class=\"col-12\"><div class=\"field-group\"><label>Service Details<span>*</span></label><div class=\"input-mock textarea-mock\">" + serviceDetails.replaceAll("\n", "<br/>") + "</div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\">" +
                    "      <div class=\"col-6\"><div class=\"field-group\"><label>Company Name<span>*</span></label><div class=\"input-mock\">" + clientOrganisation + "</div></div></div>" +
                    "      <div class=\"col-6-last\"><div class=\"field-group\"><label>Customer Name<span>*</span></label><div class=\"input-mock\">" + clientName + "</div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\">" +
                    "      <div class=\"col-6\"><div class=\"field-group\"><label>Customer Email address(es)<span>*</span></label><div class=\"input-mock\">" + String.join(", ", splitCustomerEmails) + "</div></div></div>" +
                    "      <div class=\"col-6-last\"><div class=\"field-group\"><label>Technician/Engineer Email Address<span>*</span></label><div class=\"input-mock\">" + staffEmails + "</div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\">" +
                    "      <div class=\"col-12\">" +
                    "        <div class=\"field-group\">" +
                    "          <label>File / Image upload</label>" +
                    "          <div class=\"upload-box\">" +
                                (hasValidFiles ? 
                                    "<span class=\"file-link-item\">" + zipFileName + "</span>" : 
                                    "<span class=\"no-files-text\">No files uploaded</span>"
                                ) +
                    "          </div>" +
                    "        </div>" +
                    "      </div>" +
                    "    </div>" +
                    "    <div class=\"row\">" +
                    "      <div class=\"col-12\"><div class=\"field-group\"><label>Invoiceable Service<span>*</span></label><div class=\"radio-container\"><span class=\"radio-option\">" + ("true".equalsIgnoreCase(invoiceable) ? "● Yes" : "● No") + "</span></div></div></div>" +
                    "    </div>" +
                    "    <div class=\"row\" style=\"margin-top: 10px; page-break-inside: avoid !important; break-inside: avoid !important;\">" +
                    "      <div class=\"col-12\"><div class=\"field-group\"><label>Customer Signature<span>*</span></label><div class=\"signature-frame\"><img src=\"data:image/png;base64," + cleanSignatureData + "\" style=\"max-width: 400px; height: 125px; object-fit: contain; display: block;\" /></div></div></div>" +
                    "    </div>" +
                    "    <div class=\"clear\"></div>" +
                    "  </div>" +
                    "</div>" +
                    "</body></html>";

            ByteArrayOutputStream os = new ByteArrayOutputStream();
            PdfRendererBuilder pdfBuilder = new PdfRendererBuilder();
            pdfBuilder.useFastMode(); 
            pdfBuilder.withHtmlContent(pdfHtmlTemplate, "/");
            pdfBuilder.toStream(os);
            pdfBuilder.run();
            byte[] pdfBytes = os.toByteArray();

            // Formatted email text body setup mapping extra spacing line structure rules
            String emailBodyHtml = String.format(
                "Dear %s from %s,<br/><br/>" +
                "Please find attached a copy of the Service Sheet for the Service provided today at %s. " + 
                "If you have any questions, concerns, or disagreements regarding the contents, we kindly request that you reach out to us within the next <b><u>three</u></b> working days.<br/><br/>" +
                "If we do not receive any communication from you within this designated time frame, we will consider the service sheet as accurate and satisfactory.<br/><br/>" +
                "Rest assured, we remain dedicated to resolving any potential concerns you may have, even after this period.<br/><br/>" + 
                "%s<br/>" + // <-- Passing your secure clean Cloudflare R2 link string right here
                "<br/>Best,<br/>" +
                "Nextan Service Team.<br/>" +
                "67 Ayer Rajah Crescent #04-21<br/>" +
                "+65 6872 6423",
                clientName, clientOrganisation, jobSite, zipLinkHtml
            );
            
            Content content = new Content("text/html", emailBodyHtml);

            Personalization personalization = new Personalization();
            for (String recipientEmail : recipientsList) {
                personalization.addTo(new Email(recipientEmail));
            }

            Mail mail = new Mail();
            mail.setFrom(from);
            mail.setSubject(subject);
            mail.addContent(content);
            mail.addPersonalization(personalization);

            // Attachment 1: Core PDF configuration remaining standard
            String safeFileName = "Nextan_Service_Form_" + formattedRef + ".pdf";
            Attachments pdfAttachment = new Attachments();
            pdfAttachment.setContent(Base64.getEncoder().encodeToString(pdfBytes));
            pdfAttachment.setType("application/pdf");
            pdfAttachment.setFilename(safeFileName);
            pdfAttachment.setDisposition("attachment");
            mail.addAttachments(pdfAttachment);

            // Note: Native SendGrid direct raw inline zip attachment code blocks have been removed 
            // to stop unauthenticated attachment flag payload errors from putting emails into junk.

            // Attachment 2: EML Backup Layout
            StringBuilder emlBuilder = new StringBuilder();
            emlBuilder.append("From: ").append(senderEmailString).append("\r\n");
            emlBuilder.append("To: ").append(String.join(", ", recipientsList)).append("\r\n");
            emlBuilder.append("Subject: ").append(subject).append("\r\n");
            emlBuilder.append("MIME-Version: 1.0\r\n");
            emlBuilder.append("Content-Type: text/html; charset=UTF-8\r\n\r\n");
            emlBuilder.append(emailBodyHtml);
            
            byte[] emlBytes = emlBuilder.toString().getBytes(StandardCharsets.UTF_8);
            Attachments emlAttachment = new Attachments();
            emlAttachment.setContent(Base64.getEncoder().encodeToString(emlBytes));
            emlAttachment.setType("application/octet-stream");
            emlAttachment.setFilename(subject + ".eml"); 
            emlAttachment.setDisposition("attachment");
            mail.addAttachments(emlAttachment);

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