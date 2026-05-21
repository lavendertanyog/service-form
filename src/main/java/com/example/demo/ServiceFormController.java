package com.example.demo;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

@RestController
@CrossOrigin(origins = "*")
public class ServiceFormController {

    @Value("${SENDGRID_API_KEY}")
    private String apiKey;

    @Value("${app.default-from:eunicetanyongnie@gmail.com}")
    private String defaultFrom;

    @Value("${app.finance-emails:unicorntanyongnie@gmail.com,finance2@nextan.com}")
    private String financeEmailsRaw;

    @Value("${app.staff-emails:janicelav9@gmail.com,tech2@nextan.com,engineer3@nextan.com}")
    private String staffEmailsRaw;

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://api.sendgrid.com/v3")
            .build();

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("defaultFrom", defaultFrom);
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

        try {
            List<String> customerEmails = parseRecipients(clientEmails);
            List<String> staffEmailsList = parseRecipients(staffEmails);

            if (customerEmails.isEmpty()) {
                response.put("success", false);
                response.put("error", "At least one client recipient email is required.");
                return response;
            }

            Set<String> ccEmails = new HashSet<>(staffEmailsList);
            if (invoiceable) {
                ccEmails.addAll(parseRecipients(financeEmailsRaw));
            }

            List<Map<String, String>> toList = customerEmails.stream()
                    .map(email -> Map.of("email", email))
                    .toList();

            Map<String, Object> personalizationMap = new HashMap<>();
            personalizationMap.put("to", toList);

            if (!ccEmails.isEmpty()) {
                List<Map<String, String>> ccList = ccEmails.stream()
                        .map(email -> Map.of("email", email))
                        .toList();
                personalizationMap.put("cc", ccList);
            }

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
               "==================================================";

            Map<String, Object> fromDetails = Map.of("email", defaultFrom, "name", "Nextan Service Team");
            Map<String, Object> contentDetails = Map.of("type", "text/plain", "value", 
                    "Hello,\n\nPlease find the completed service form summary details documentation pack attached.\n\nThank you.");
            
            List<Map<String, String>> attachmentsList = new ArrayList<>();
            
            String encodedSummary = Base64.getEncoder().encodeToString(summaryText.getBytes());
            attachmentsList.add(Map.of("content", encodedSummary, "type", "text/plain", "filename", "service-form-summary.txt"));

            if (signatureBase64 != null && signatureBase64.contains(",")) {
                String cleanBase64 = signatureBase64.split(",")[1];
                attachmentsList.add(Map.of("content", cleanBase64, "type", "image/png", "filename", "customer-signature.png"));
            }

            if (files != null && files.length > 0 && !files[0].isEmpty()) {
                byte[] zipBytes = createZipArchive(files);
                String encodedZip = Base64.getEncoder().encodeToString(zipBytes);
                attachmentsList.add(Map.of("content", encodedZip, "type", "application/zip", "filename", "service-form-attachments.zip"));
            }

            Map<String, Object> sendGridPayload = new HashMap<>();
            sendGridPayload.put("personalizations", List.of(personalizationMap));
            sendGridPayload.put("from", fromDetails);
            sendGridPayload.put("subject", "Service Form for " + clientName + (invoiceable ? " (Invoiceable)" : ""));
            sendGridPayload.put("content", List.of(contentDetails));
            if (!attachmentsList.isEmpty()) {
                sendGridPayload.put("attachments", attachmentsList);
            }

            restClient.post()
                    .uri("/mail/send")
                    .header("Authorization", "Bearer " + apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(sendGridPayload)
                    .retrieve()
                    .toBodilessEntity();

            response.put("success", true);
            return response;

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "REST API Engine Error: " + e.getMessage());
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