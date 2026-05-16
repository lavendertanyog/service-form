package com.example.demo;

import java.io.ByteArrayOutputStream;
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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.mail.internet.MimeMessage;

@RestController
@CrossOrigin(origins = "*") 
public class ServiceFormController {

    @Autowired
    private JavaMailSender mailSender;

    @Value("${app.default-from}")
    private String defaultFrom;

    @Value("${app.finance-emails}")
    private String financeEmailsRaw;

    @Value("${app.staff-emails}")
    private String staffEmailsRaw;

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
            List<String> customerRecipients = parseRecipients(clientEmails);
            List<String> staffRecipients = parseRecipients(staffEmails);
            
            if (customerRecipients.isEmpty()) {
                response.put("success", false);
                response.put("error", "At least one client recipient email is required.");
                return response;
            }

            Set<String> ccRecipients = new HashSet<>(staffRecipients);
            if (invoiceable) {
                ccRecipients.addAll(parseRecipients(financeEmailsRaw));
            }

            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(defaultFrom);
            helper.setTo(customerRecipients.toArray(new String[0]));
            if (!ccRecipients.isEmpty()) {
                helper.setCc(ccRecipients.toArray(new String[0]));
            }
            
            String subject = "Service Form for " + clientName + (invoiceable ? " (Invoiceable)" : "");
            helper.setSubject(subject);

            String formSummaryText = buildSummaryText(jobSite, location, serviceDate, serviceTime, 
                    serviceRequest, serviceDetails, clientOrganisation, clientName, clientEmails, staffEmails, invoiceable);

            String emailBodyText = "Hello,\n\nPlease find the completed service form summary details documentation pack attached.\n\n"
                    + "Client Profile: " + clientName + " (" + clientOrganisation + ")\n"
                    + "Job Site Target: " + jobSite + "\n\n"
                    + "If layout changes are required, please respond to this email.\n\nThank you.";
            helper.setText(emailBodyText);

            helper.addAttachment("service-form-summary.txt", new ByteArrayResource(formSummaryText.getBytes()));

            if (signatureBase64 != null && signatureBase64.contains(",")) {
                byte[] sigImageBytes = Base64.getDecoder().decode(signatureBase64.split(",")[1]);
                helper.addAttachment("customer-signature.png", new ByteArrayResource(sigImageBytes));
            }

            if (files != null && files.length > 0 && !files[0].isEmpty()) {
                byte[] zipBytes = createZipArchive(files);
                helper.addAttachment("service-form-attachments.zip", new ByteArrayResource(zipBytes));

                for (MultipartFile file : files) {
                    if (!file.isEmpty()) {
                        helper.addAttachment(Objects.requireNonNull(file.getOriginalFilename()), file);
                    }
                }
            }

            mailSender.send(message);

            response.put("success", true);
            response.put("sentTo", customerRecipients);
            response.put("cc", ccRecipients);
            return response;

        } catch (Exception e) {
            response.put("success", false);
            response.put("error", "Email pipeline error: " + e.getMessage());
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

    private String buildSummaryText(String jobSite, String location, String date, String time, String request, 
                                    String details, String org, String name, String clientEmail, String staffEmail, boolean inv) {
        return "==================================================\n" +
               "               NEXTAN SERVICE FORM SUMMARY        \n" +
               "==================================================\n" +
               "Client Profile name    : " + name + "\n" +
               "Client Organisation    : " + org + "\n" +
               "Client Core Emails     : " + clientEmail + "\n" +
               "--------------------------------------------------\n" +
               "Job Site Location      : " + jobSite + " (" + location + ")\n" +
               "Execution Timestamp    : " + date + " @ " + time + "\n" +
               "--------------------------------------------------\n" +
               "Service Request Line   : " + request + "\n" +
               "Detailed Service Logs  : \n" + details + "\n" +
               "--------------------------------------------------\n" +
               "Attending Staff Email  : " + staffEmail + "\n" +
               "Invoiceable Flagged    : " + (inv ? "YES" : "NO") + "\n" +
               "==================================================";
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
