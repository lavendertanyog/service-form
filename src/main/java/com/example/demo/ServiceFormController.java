package com.example.demo;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@CrossOrigin(origins = "*")
public class ServiceFormController {

    @Autowired
    private JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${app.finance-emails}")
    private String financeEmailsRaw;

    @Value("${app.staff-emails}")
    private String staffEmailsRaw;

    @GetMapping("/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("defaultFrom", fromEmail);
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

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail, "Nextan Service Team");
            helper.setTo(customerEmails.toArray(new String[0]));

            Set<String> ccEmails = new HashSet<>(staffEmailsList);
            if (invoiceable) {
                ccEmails.addAll(parseRecipients(financeEmailsRaw));
            }
            if (!ccEmails.isEmpty()) {
                helper.setCc(ccEmails.toArray(new String[0]));
            }

            helper.setSubject("Service Form for " + clientName + (invoiceable ? " (Invoiceable)" : ""));
            helper.setText("Hello,\n\nPlease find the completed service form details documentation pack attached.\n\nThank you.");

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

            helper.addAttachment("service-form-summary.txt", new ByteArrayResource(summaryText.getBytes()));

            if (signatureBase64 != null && signatureBase64.contains(",")) {
                String cleanBase64 = signatureBase64.split(",")[1];
                byte[] decodedSignature = Base64.getDecoder().decode(cleanBase64);
                helper.addAttachment("customer-signature.png", new ByteArrayResource(decodedSignature));
            }

            if (files != null && files.length > 0 && !files[0].isEmpty()) {
                byte[] zipBytes = createZipArchive(files);
                helper.addAttachment("service-form-attachments.zip", new ByteArrayResource(zipBytes));
            }

            mailSender.send(message);
            response.put("success", true);
            return response;

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "SMTP Server Error: " + e.getMessage());
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
