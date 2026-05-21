package com.example.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@CrossOrigin(origins = "*")
public class ServiceFormController {

    @Value("${SENDGRID_API_KEY:}")
    private String sendGridApiKey;

    @Value("${app.default-from}")
    private String defaultFromEmail;

    @Value("${app.finance-emails}")
    private String financeEmailsRaw;

    @Value("${app.staff-emails}")
    private String staffEmailsRaw;

    private final RestTemplate restTemplate = new RestTemplate();

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("defaultFrom", defaultFromEmail);
        config.put("financeEmails", parseRecipients(financeEmailsRaw));
        config.put("staffOptions", parseRecipients(staffEmailsRaw));
        return config;
    }

    @PostMapping("/submit")
    public Map<String, Object> handleSubmit(
            @RequestParam("jobSite") String jobSite,
            @RequestParam("location") String location,
            @RequestParam("serviceDate") String serviceDate,
            @RequestParam("serviceTime") String serviceTime,
            @RequestParam("serviceRequest") String serviceRequest,
            @RequestParam("serviceDetails") String serviceDetails,
            @RequestParam("clientOrganisation") String clientOrganisation,
            @RequestParam("clientName") String clientName,
            @RequestParam("clientEmails") String clientEmails,
            @RequestParam("staffEmails") String staffEmails,
            @RequestParam("invoiceable") boolean invoiceable,
            @RequestParam(value = "signature", required = false) String signatureBase64,
            @RequestParam(value = "attachments", required = false) MultipartFile[] files) {

        Map<String, Object> response = new HashMap<>();

        if (sendGridApiKey == null || sendGridApiKey.trim().isEmpty()) {
            response.put("success", false);
            response.put("error", "Backend Configuration Error: SendGrid API Key is missing on the hosting platform.");
            return response;
        }

        try {
            List<String> customerEmails = parseRecipients(clientEmails);
            List<String> staffEmailsList = parseRecipients(staffEmails);

            if (customerEmails.isEmpty()) {
                response.put("success", false);
                response.put("error", "At least one client recipient email is required.");
                return response;
            }

            // Assemble Text Content Pack
            String summaryText = "==================================================\n" +
               "               NEXTAN SERVICE FORM SUMMARY        \n" +
               "==================================================\n" +
               "Client Profile name    : " + clientName + "\n" +
               "Client Organisation    : " + clientOrganisation + "\n" +
               "Client Core Emails     : " + clientEmails + "\n" +
               "--------------------------------------------------\n" +
               "Job Site Location      : " + jobSite + " (" + location + ")\n" +
               "Execution Timestamp    : " + serviceDate + " @ " + serviceTime + "\n" +
               "--------------------------------------------------\n" +
               "Service Request Line   : " + serviceRequest + "\n" +
               "Detailed Service Logs  : \n" + serviceDetails + "\n" +
               "--------------------------------------------------\n" +
               "Attending Staff Email  : " + staffEmails + "\n" +
               "Invoiceable Flagged    : " + (invoiceable ? "YES" : "NO") + "\n" +
               "==================================================\n";

            // Prepare SendGrid Request Body
            Map<String, Object> requestBody = new HashMap<>();
            
            // Personalizations (To, CC)
            List<Map<String, Object>> personalizations = new ArrayList<>();
            Map<String, Object> personalization = new HashMap<>();
            
            List<Map<String, String>> toList = new ArrayList<>();
            for (String email : customerEmails) {
                toList.add(Map.of("email", email));
            }
            personalization.put("to", toList);

            List<Map<String, String>> ccList = new ArrayList<>();
            for (String email : staffEmailsList) {
                ccList.add(Map.of("email", email));
            }
            if (invoiceable) {
                for (String email : parseRecipients(financeEmailsRaw)) {
                    ccList.add(Map.of("email", email));
                }
            }
            if (!ccList.isEmpty()) {
                personalization.put("cc", ccList);
            }
            personalizations.add(personalization);
            requestBody.put("personalizations", personalizations);

            // Metadata Subject Line
            String subject = "Service Form for " + clientName + (invoiceable ? " (Invoiceable)" : "");
            requestBody.put("subject", subject);

            // Verified From Sender Identity
            requestBody.put("from", Map.of("email", defaultFromEmail, "name", "Nextan Service Team"));

            // Text Body
            requestBody.put("content", List.of(Map.of(
                "type", "text/plain",
                "value", "Hello,\n\nPlease find the completed service form details documentation pack attached.\n\nThank you."
            )));

            // Process Attachments (Text File, Signature, and Zip Archive)
            List<Map<String, String>> attachments = new ArrayList<>();
            
            // 1. Text Summary Attachment
            String base64Summary = Base64.getEncoder().encodeToString(summaryText.getBytes());
            attachments.add(Map.of("content", base64Summary, "filename", "service-form-summary.txt", "type", "text/plain"));

            // 2. Signature Image Attachment
            if (signatureBase64 != null && signatureBase64.contains(",")) {
                String cleanBase64 = signatureBase64.split(",")[1];
                attachments.add(Map.of("content", cleanBase64, "filename", "customer-signature.png", "type", "image/png"));
            }

            // 3. Multi-file Zip Attachment
            if (files != null && files.length > 0 && !files[0].isEmpty()) {
                byte[] zipBytes = createZipArchive(files);
                String base64Zip = Base64.getEncoder().encodeToString(zipBytes);
                attachments.add(Map.of("content", base64Zip, "filename", "service-form-attachments.zip", "type", "application/zip"));
            }

            if (!attachments.isEmpty()) {
                requestBody.put("attachments", attachments);
            }

            // Dispatched over Secure REST Traffic (Port 443)
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(sendGridApiKey);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> apiResponse = restTemplate.postForEntity("https://api.sendgrid.com/v3/mail/send", entity, String.class);

            if (apiResponse.getStatusCode().is2xxSuccessful()) {
                response.put("success", true);
            } else {
                response.put("success", false);
                response.put("error", "SendGrid API Rejected Request: " + apiResponse.getBody());
            }
            return response;

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "Application Exception Error: " + e.getMessage());
            return response;
        }
    }

    private List<String> parseRecipients(String value) {
        if (value == null || value.trim().isEmpty()) return Collections.emptyList();
        return Arrays.stream(value.split("[,;\\n]+"))
                .map(String::trim)
                .filter(item -> !item.isEmpty())
                .toList();
    }

    private byte[] createZipArchive(MultipartFile[] files) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            for (MultipartFile file : files) {
                if (file.isEmpty()) continue;
                ZipEntry entry = new ZipEntry(Objects.requireNonNull(file.getOriginalFilename()));
                zos.putNextEntry(entry);
                zos.write(file.getBytes());
                zos.closeEntry();
            }
        }
        return baos.toByteArray();
    }
}
