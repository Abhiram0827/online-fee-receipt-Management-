package com.example.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.twilio.Twilio;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.PostConstruct;
import java.util.*;

@SpringBootApplication
@EnableScheduling
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") 
public class CollegeApp {

    private final JdbcTemplate db;
    private final JavaMailSender mailSender;

    @Value("${app.twilio.sid:#{null}}") String twilioSid;
    @Value("${app.twilio.token:#{null}}") String twilioToken;
    @Value("${spring.mail.username:admin@college.com}") String emailFrom;

    public CollegeApp(JdbcTemplate db, JavaMailSender mailSender) {
        this.db = db;
        this.mailSender = mailSender;
    }

    public static void main(String[] args) {
        SpringApplication.run(CollegeApp.class, args);
    }

    // --- 1. TRUE Global CORS Fix (Stops Spring Boot from hiding 404 errors) ---
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**").allowedOrigins("*").allowedMethods("*");
            }
        };
    }

    // --- 2. Safe Number Parser (Stops backend crashing on empty text) ---
    private double parseAmount(Object value) {
        if (value == null || value.toString().trim().isEmpty()) return 0.0;
        try { return Double.parseDouble(value.toString()); } 
        catch (Exception e) { return 0.0; }
    }
    
    // --- 3. Auto-Increment Fallback (Fixes "Field 'id' doesn't have a default value") ---
    private synchronized int getNextId(String tableName) {
        Integer maxId = db.queryForObject("SELECT MAX(id) FROM " + tableName, Integer.class);
        return (maxId == null ? 0 : maxId) + 1;
    }

    // --- 4. Dynamic Chronological Year Calculator (Fixes "Passed Out" Filter) ---
    private int calculateCurrentAcademicYear(String collegeId, String course) {
        if (collegeId == null || collegeId.trim().length() < 2) return 1;
        try {
            int admissionYearShort = Integer.parseInt(collegeId.trim().substring(0, 2));
            int admissionYear = 2000 + admissionYearShort;
            
            // Adjust for Lateral Entry Students (usually have '5' in the 5th character position)
            if (collegeId.length() >= 5 && collegeId.charAt(4) == '5') {
                admissionYear -= 1; // Align them chronologically with their peer batch
            }
            
            int courseDuration = (course != null && course.trim().equalsIgnoreCase("DIPLOMA")) ? 3 : 4;
            
            java.time.LocalDate today = java.time.LocalDate.now();
            int currentYear = today.getYear();
            int currentMonth = today.getMonthValue();
            
            int yearsElapsed = currentYear - admissionYear;
            if (currentMonth > 6) { // Academic year shifts in July
                yearsElapsed += 1;
            }
            
            int calcYear = Math.max(1, yearsElapsed);
            
            // Frontend hardcodes `> 4` for passed out filters. 
            // If a Diploma student is passed out (calcYear > 3), force it to be at least 5 to trigger the frontend logic.
            if (courseDuration == 3 && calcYear > 3) {
                calcYear = Math.max(5, calcYear); 
            }
            
            return calcYear;
        } catch (Exception e) {
            return 1;
        }
    }

    // --- Global Error Handler (Stops 'Failed to fetch' browser crashes) ---
    @ExceptionHandler(Exception.class)
    public ApiResponse handleAllExceptions(Exception ex) {
        ex.printStackTrace(); 
        return new ApiResponse(false, "Backend Error: " + ex.getMessage(), new ArrayList<>());
    }

    // --- Java Records ---
    record AuthRequest(String loginId, String password, String collegeId, String name, String course, String phone, String email) {}
    record ApiResponse(boolean success, String message, Object data) {}

    // --- System Initialization & Auto-Table Creation ---
    @PostConstruct
    public void initApp() {
        if (twilioSid != null && twilioToken != null) Twilio.init(twilioSid, twilioToken);

        // Auto-Create Database Tables so the frontend never crashes on missing data
        db.execute("CREATE TABLE IF NOT EXISTS users (id INT AUTO_INCREMENT PRIMARY KEY, college_id VARCHAR(50) UNIQUE, full_name VARCHAR(100), course VARCHAR(50), phone_number VARCHAR(20) UNIQUE, email VARCHAR(100), password VARCHAR(255), role VARCHAR(20))");
        db.execute("CREATE TABLE IF NOT EXISTS students (id INT AUTO_INCREMENT PRIMARY KEY, collegeId VARCHAR(50), paidAmount DOUBLE, dueDate DATE, currentAcademicYear INT)");
        db.execute("CREATE TABLE IF NOT EXISTS fee_structures (id INT AUTO_INCREMENT PRIMARY KEY, studentId VARCHAR(50), academic_year INT, fee_type VARCHAR(50), amount_due DOUBLE)");
        db.execute("CREATE TABLE IF NOT EXISTS daily_transactions (id INT AUTO_INCREMENT PRIMARY KEY, date DATE, amount DOUBLE, description TEXT, type VARCHAR(20))");
        db.execute("CREATE TABLE IF NOT EXISTS payments (id INT AUTO_INCREMENT PRIMARY KEY, studentId VARCHAR(50), type VARCHAR(50), amount DOUBLE, date DATE, year INT, description TEXT)");

        // Schema Repair: Automatically patches tables if they were previously created without AUTO_INCREMENT
        try {
            db.execute("ALTER TABLE users MODIFY id INT AUTO_INCREMENT");
            db.execute("ALTER TABLE students MODIFY id INT AUTO_INCREMENT");
            db.execute("ALTER TABLE fee_structures MODIFY id INT AUTO_INCREMENT");
            db.execute("ALTER TABLE daily_transactions MODIFY id INT AUTO_INCREMENT");
            db.execute("ALTER TABLE payments MODIFY id INT AUTO_INCREMENT");
        } catch (Exception ignored) {} // Catch and ignore if already auto-increment or unsupported DB

        // Seed Default Dashboards
        seedDefaultUser("admin", "admin123", "System Admin", "admin", null);
        seedDefaultUser("management", "mgmt123", "Management Head", "management_head", null);
        seedDefaultUser("dept_cse", "cse123", "CSE Department", "department", "CSE");
        seedDefaultUser("dept_csd", "csd123", "CSD Department", "department", "CSD");
        seedDefaultUser("dept_csm", "csm123", "CSM Department", "department", "CSM");
        seedDefaultUser("dept_ece", "ece123", "ECE Department", "department", "ECE");
        seedDefaultUser("dept_diploma", "diploma123", "Diploma Department", "department", "DIPLOMA");
        seedDefaultUser("dept_sh", "sh123", "S&H Department", "department", "S&H");
    }

    private void seedDefaultUser(String loginId, String password, String fullName, String role, String course) {
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(10));
        List<Map<String, Object>> exists = db.queryForList("SELECT id FROM users WHERE college_id = ?", loginId);
        
        if (exists.isEmpty()) {
            // FIX: Generate a unique dummy phone number based on the loginId
            String dummyPhone = "000-" + loginId;
            
            db.update("INSERT INTO users (id, college_id, full_name, password, role, course, phone_number, email) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    getNextId("users"), loginId, fullName, hash, role, course, dummyPhone, loginId + "@college.edu");
        } else {
            db.update("UPDATE users SET password = ?, role = ?, course = ? WHERE college_id = ?", hash, role, course, loginId);
        }
    }

    // --- 1. User Signup ---
    @PostMapping("/signup")
    public ApiResponse signup(@RequestBody AuthRequest req) {
        List<Map<String, Object>> exists = db.queryForList("SELECT id FROM users WHERE college_id = ?", req.collegeId());
        if (!exists.isEmpty()) return new ApiResponse(false, "User already exists.", null);

        String hash = BCrypt.hashpw(req.password(), BCrypt.gensalt(10));
        db.update("INSERT INTO users (id, college_id, full_name, course, phone_number, email, password, role) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                getNextId("users"), req.collegeId(), req.name(), req.course(), req.phone(), req.email(), hash, "student");
        
        db.update("INSERT INTO students (id, collegeId, paidAmount, dueDate, currentAcademicYear) VALUES (?, ?, 0, DATE_ADD(CURDATE(), INTERVAL 1 MONTH), 1)", 
                getNextId("students"), req.collegeId());
                
        String[] defaultFees = {"Tuition Fee", "Registration Fee", "Special Fee", "Transportation Fee", "Hostel fee", "Other fee"};
        for (String fee : defaultFees) {
            double amount = fee.equals("Tuition Fee") ? 20000 : (fee.equals("Registration Fee") ? 5000 : 0);
            db.update("INSERT INTO fee_structures (id, studentId, academic_year, fee_type, amount_due) VALUES (?, ?, 1, ?, ?)", 
                    getNextId("fee_structures"), req.collegeId(), fee, amount);
        }
        return new ApiResponse(true, "Registration successful!", null);
    }

    // --- 2. User Login ---
    @PostMapping("/login")
    public ApiResponse login(@RequestBody AuthRequest req) {
        List<Map<String, Object>> users = db.queryForList("SELECT * FROM users WHERE college_id = ?", req.loginId());
        if (users.isEmpty()) return new ApiResponse(false, "Invalid credentials.", null);

        Map<String, Object> user = users.get(0);
        if (!BCrypt.checkpw(req.password(), (String) user.get("password"))) {
            return new ApiResponse(false, "Invalid credentials.", null);
        }

        user.remove("password");
        return new ApiResponse(true, "Login successful!", user);
    }

    // --- 3. Get All Students ---
    @GetMapping("/students")
    public ApiResponse getStudents() {
        return new ApiResponse(true, "Success", db.queryForList("SELECT college_id, full_name, course FROM users WHERE role = 'student'"));
    }

    // --- 4. Get Specific Student (For Dashboard) ---
    @GetMapping("/student/{collegeId}")
    public ApiResponse getStudentDetails(@PathVariable String collegeId) {
        List<Map<String, Object>> users = db.queryForList("SELECT * FROM users WHERE college_id = ?", collegeId);
        if (users.isEmpty()) return new ApiResponse(false, "Student not found.", null);
        
        Map<String, Object> user = users.get(0);
        List<Map<String, Object>> students = db.queryForList("SELECT * FROM students WHERE collegeId = ?", collegeId);
        Map<String, Object> studentInfo = students.isEmpty() ? new HashMap<>() : students.get(0);

        List<Map<String, Object>> fees = db.queryForList("SELECT * FROM fee_structures WHERE studentId = ?", collegeId);
        Map<Integer, List<Map<String, Object>>> detailedSummary = new HashMap<>();
        
        for (Map<String, Object> fee : fees) {
            Integer year = ((Number) fee.get("academic_year")).intValue();
            detailedSummary.putIfAbsent(year, new ArrayList<>());
            Map<String, Object> feeDetail = new HashMap<>();
            
            String feeType = (String) fee.get("fee_type");
            double amountDue = ((Number) fee.get("amount_due")).doubleValue();
            
            // Calculate actual paid amount for this specific fee type and academic year by querying the payments table
            Double paid = db.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payments WHERE studentId = ? AND type = ? AND year = ?", 
                Double.class, collegeId, feeType, year);
            double amountPaid = paid != null ? paid : 0.0;
            
            feeDetail.put("feeType", feeType);
            feeDetail.put("amountDue", amountDue);
            feeDetail.put("amountPaid", amountPaid); 
            feeDetail.put("pendingAmount", amountDue - amountPaid);
            
            detailedSummary.get(year).add(feeDetail);
        }

        Map<String, Object> responseData = new HashMap<>(user);
        responseData.put("paidAmount", studentInfo.getOrDefault("paidAmount", 0));
        responseData.put("dueDate", studentInfo.get("dueDate"));
        
        // Dynamic Passed Out Calculator
        int dynamicYear = calculateCurrentAcademicYear(collegeId, (String) user.get("course"));
        responseData.put("currentAcademicYear", dynamicYear);
        
        responseData.put("detailedSummary", detailedSummary);
        
        // Fetch actual payment history to populate the client's dashboard
        List<Map<String, Object>> payments = db.queryForList("SELECT * FROM payments WHERE studentId = ? ORDER BY date DESC", collegeId);
        responseData.put("payments", payments); 

        return new ApiResponse(true, "Success", responseData);
    }

    // --- Official Receipt Generator (Dynamic Academic Year Format) ---
    @GetMapping(value = {"/receipt/{id}", "/student/receipt/{id}", "/download-receipt/{id}", "/payment/receipt/{id}"}, produces = "text/html")
    public String downloadReceipt(@PathVariable int id) {
        // Fetch payment alongside student details using a JOIN
        List<Map<String, Object>> records = db.queryForList(
                "SELECT p.*, u.full_name, u.course FROM payments p JOIN users u ON p.studentId = u.college_id WHERE p.id = ?", id);
        
        if (records.isEmpty()) return "<html><body><h2>Receipt not found!</h2></body></html>";
        
        Map<String, Object> p = records.get(0);
        
        // --- Data Extraction & Formatting ---
        String studentName = p.get("full_name") != null ? (String) p.get("full_name") : "N/A";
        String studentId = p.get("studentId") != null ? (String) p.get("studentId") : "N/A";
        String course = p.get("course") != null ? (String) p.get("course") : "N/A";
        
        // Dynamically calculate the span (e.g. 2023-24) based on admission year and the fee year
        int academicYear = p.get("year") != null ? ((Number) p.get("year")).intValue() : 1;
        
        int admissionYear = 2024; // Default fallback
        if (studentId != null && studentId.length() >= 2) {
            try {
                admissionYear = 2000 + Integer.parseInt(studentId.substring(0, 2));
                // Lateral Entry correction
                if (studentId.length() >= 5 && studentId.charAt(4) == '5') {
                    admissionYear -= 1;
                }
            } catch (NumberFormatException e) {}
        }
        
        int feeStartYear = admissionYear + (academicYear - 1);
        String nextYearShort = String.format("%02d", (feeStartYear + 1) % 100);
        
        String[] suffixes = new String[] { "th", "st", "nd", "rd", "th", "th", "th", "th", "th", "th" };
        String suffix = (academicYear % 100 > 10 && academicYear % 100 < 14) ? "th" : suffixes[academicYear % 10];
        
        // Final Dynamic String (e.g. "2023-24 (1st Year)")
        String acYearText = feeStartYear + "-" + nextYearShort + " (" + academicYear + suffix + " Year)";
        
        // Format Receipt No (e.g., ASIST/24-25/REC0001)
        String receiptPrefix = String.format("ASIST/%02d-%02d/REC", feeStartYear % 100, (feeStartYear + 1) % 100);
        String receiptNo = String.format("%s%04d", receiptPrefix, p.get("id"));
        
        // Format Date (DD/MM/YYYY)
        String formattedDate = p.get("date") != null ? p.get("date").toString() : "";
        try {
            java.time.LocalDate ld = java.time.LocalDate.parse(formattedDate);
            formattedDate = ld.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        } catch (Exception e) {}
        
        String desc = p.get("description") != null ? (String) p.get("description") : "Fee Payment";
        double amount = p.get("amount") != null ? ((Number) p.get("amount")).doubleValue() : 0.0;
        String amountStr = String.format("%.2f", amount);

        // --- HTML & CSS Construction ---
        return "<!DOCTYPE html>\n" +
               "<html lang=\"en\">\n" +
               "<head>\n" +
               "    <meta charset=\"UTF-8\">\n" +
               "    <title>Fee Payment Receipt</title>\n" +
               "    <style>\n" +
               "        body { font-family: 'Times New Roman', Times, serif; background: #525659; display: flex; justify-content: center; padding: 20px; margin: 0; }\n" +
               "        .receipt-container { background: white; width: 210mm; min-height: 297mm; padding: 40px; box-sizing: border-box; box-shadow: 0 0 10px rgba(0,0,0,0.5); position: relative; margin: auto; }\n" +
               "        .header { text-align: center; border-bottom: 2px solid #000; padding-bottom: 15px; margin-bottom: 30px; }\n" +
               "        .college-name { font-size: 24px; font-weight: bold; color: #8b0000; margin: 0; }\n" +
               "        .sub-text { font-size: 14px; margin: 5px 0; }\n" +
               "        .address { font-size: 13px; font-weight: bold; margin: 5px 0; }\n" +
               "        .title { text-align: center; font-size: 20px; font-weight: bold; text-decoration: underline; margin: 30px 0; }\n" +
               "        .info-grid { display: flex; justify-content: space-between; margin-bottom: 40px; font-size: 15px; }\n" +
               "        .info-col { width: 48%; line-height: 2; }\n" +
               "        .info-row { display: flex; }\n" +
               "        .info-label { font-weight: bold; width: 130px; }\n" +
               "        .info-value { flex: 1; text-transform: uppercase; }\n" +
               "        table { width: 100%; border-collapse: collapse; margin-top: 20px; }\n" +
               "        th, td { border: 1px solid #000; padding: 12px; text-align: left; font-size: 15px; }\n" +
               "        th { background-color: #f9fafb; font-weight: bold; }\n" +
               "        .amount-col { text-align: right; width: 150px; }\n" +
               "        .total-row { font-weight: bold; }\n" +
               "        .footer { position: absolute; bottom: 40px; left: 0; width: 100%; font-size: 12px; text-align: center; }\n" +
               "        .signature { position: absolute; bottom: 100px; right: 60px; text-align: right; font-weight: bold; font-size: 16px; }\n" +
               "        .watermark { position: absolute; top: 50%; left: 50%; transform: translate(-50%, -50%); opacity: 0.08; text-align: center; border: 12px solid #000; border-radius: 50%; width: 400px; height: 400px; display: flex; flex-direction: column; justify-content: center; align-items: center; pointer-events: none; }\n" +
               "        .watermark h1 { font-size: 80px; margin: 0; letter-spacing: 5px; color: #000; }\n" +
               "        .watermark p { font-size: 22px; margin: 10px 0 0; font-weight: bold; color: #000; text-align: center; padding: 0 20px; }\n" +
               "        @media print { body { background: white; padding: 0; } .receipt-container { box-shadow: none; width: 100%; height: 100%; border: none; } .no-print { display: none; } }\n" +
               "        .print-btn { position: fixed; top: 20px; right: 20px; padding: 10px 20px; background: #3b82f6; color: white; border: none; border-radius: 5px; cursor: pointer; font-size: 16px; box-shadow: 0 2px 4px rgba(0,0,0,0.2); z-index: 1000; }\n" +
               "        .print-btn:hover { background: #2563eb; }\n" +
               "    </style>\n" +
               "</head>\n" +
               "<body>\n" +
               "    <button class=\"print-btn no-print\" onclick=\"window.print()\">🖨️ Print Receipt</button>\n" +
               "    <div class=\"receipt-container\">\n" +
               "        <div class=\"watermark\">\n" +
               "            <h1>PAID</h1>\n" +
               "            <p>AMRITA SAI INSTITUTE OF SCIENCE AND TECHNOLOGY</p>\n" +
               "        </div>\n" +
               "        <div class=\"header\">\n" +
               "            <h1 class=\"college-name\">AMRITA SAI INSTITUTE OF SCIENCE & TECHNOLOGY</h1>\n" +
               "            <p class=\"sub-text\">(Approved by AICTE, New Delhi, Permanently Affiliated to JNTUK, Kakinada)</p>\n" +
               "            <p class=\"address\">Amrita Sai Nagar, Paritala (Post), Kanchikacherla (Mandal), NTR (Dist) - 521180</p>\n" +
               "        </div>\n" +
               "        \n" +
               "        <div class=\"title\">FEE PAYMENT RECEIPT</div>\n" +
               "        \n" +
               "        <div class=\"info-grid\">\n" +
               "            <div class=\"info-col\">\n" +
               "                <div class=\"info-row\"><span class=\"info-label\">Student Name:</span><span class=\"info-value\">" + studentName + "</span></div>\n" +
               "                <div class=\"info-row\"><span class=\"info-label\">College ID:</span><span class=\"info-value\">" + studentId + "</span></div>\n" +
               "                <div class=\"info-row\"><span class=\"info-label\">Course:</span><span class=\"info-value\">" + course + "</span></div>\n" +
               "                <div class=\"info-row\"><span class=\"info-label\">Academic Year:</span><span class=\"info-value\">" + acYearText + "</span></div>\n" +
               "            </div>\n" +
               "            <div class=\"info-col\">\n" +
               "                <div class=\"info-row\"><span class=\"info-label\">Receipt No:</span><span class=\"info-value\">" + receiptNo + "</span></div>\n" +
               "                <div class=\"info-row\"><span class=\"info-label\">Date:</span><span class=\"info-value\">" + formattedDate + "</span></div>\n" +
               "            </div>\n" +
               "        </div>\n" +
               "        \n" +
               "        <table>\n" +
               "            <thead>\n" +
               "                <tr>\n" +
               "                    <th>Description</th>\n" +
               "                    <th class=\"amount-col\">Amount (₹)</th>\n" +
               "                </tr>\n" +
               "            </thead>\n" +
               "            <tbody>\n" +
               "                <tr>\n" +
               "                    <td>" + desc + "</td>\n" +
               "                    <td class=\"amount-col\">" + amountStr + "</td>\n" +
               "                </tr>\n" +
               "                <tr class=\"total-row\">\n" +
               "                    <td style=\"text-align: right;\">Total Paid</td>\n" +
               "                    <td class=\"amount-col\">" + amountStr + "</td>\n" +
               "                </tr>\n" +
               "            </tbody>\n" +
               "        </table>\n" +
               "        \n" +
               "        <div class=\"signature\">\n" +
               "            <p>Authorized Signatory</p>\n" +
               "        </div>\n" +
               "        \n" +
               "        <div class=\"footer\">\n" +
               "            This is a computer-generated receipt and does not require a physical signature.\n" +
               "        </div>\n" +
               "    </div>\n" +
               "</body>\n" +
               "</html>";
    }

    // --- Process a New Fee Payment (CATCH-ALL ENDPOINTS) ---
    @PostMapping({"/payments", "/pay-fee", "/student/pay", "/student/pay-fee"})
    public ApiResponse processPayment(@RequestBody Map<String, Object> req) {
        try {
            String studentId = (String) req.get("studentId");
            String feeType = (String) req.get("feeType");
            
            // Uses safe number parser
            double amount = parseAmount(req.get("amount"));
            
            int year = 1;
            if (req.get("year") != null && !req.get("year").toString().isEmpty()) {
                year = Integer.parseInt(req.get("year").toString());
            }
            
            String date = (String) req.get("date"); 
            String desc = (String) req.get("description");

            db.update("INSERT INTO payments (id, studentId, type, amount, date, year, description) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    getNextId("payments"), studentId, feeType, amount, date, year, desc);

            db.update("UPDATE students SET paidAmount = paidAmount + ? WHERE collegeId = ?", amount, studentId);

            // FIX: Force 'credit' to be lowercase for matching the frontend's case sensitivity check
            db.update("INSERT INTO daily_transactions (id, date, amount, description, type) VALUES (?, ?, ?, ?, 'credit')",
                    getNextId("daily_transactions"), date, amount, "Fee Payment: " + studentId + " - " + feeType);

            return new ApiResponse(true, "Payment processed successfully!", null);
        } catch (Exception e) {
            e.printStackTrace();
            return new ApiResponse(false, "Payment Error: " + e.getMessage(), null);
        }
    }

    // --- Update Student Fee Structure & Add Payment ---
    @PostMapping("/student/update-fees")
    @SuppressWarnings("unchecked")
    public ApiResponse updateStudentFees(@RequestBody Map<String, Object> req) {
        try {
            String studentId = (String) req.get("studentId");
            Integer academicYear = (Integer) req.get("academicYear");

            // 1. Update the Fee Structure amounts
            List<Map<String, Object>> feeStructure = (List<Map<String, Object>>) req.get("feeStructure");
            if (feeStructure != null) {
                for (Map<String, Object> fee : feeStructure) {
                    String type = (String) fee.get("type");
                    double amount = parseAmount(fee.get("amount"));

                    // Check if this fee type already exists for this year
                    List<Map<String, Object>> existing = db.queryForList(
                            "SELECT id FROM fee_structures WHERE studentId = ? AND academic_year = ? AND fee_type = ?",
                            studentId, academicYear, type);

                    if (existing.isEmpty()) {
                        db.update("INSERT INTO fee_structures (id, studentId, academic_year, fee_type, amount_due) VALUES (?, ?, ?, ?, ?)",
                                getNextId("fee_structures"), studentId, academicYear, type, amount);
                    } else {
                        db.update("UPDATE fee_structures SET amount_due = ? WHERE studentId = ? AND academic_year = ? AND fee_type = ?",
                                amount, studentId, academicYear, type);
                    }
                }
            }

            // 2. Process any new payment recorded in the modal
            Map<String, Object> payment = (Map<String, Object>) req.get("payment");
            if (payment != null) {
                double paymentAmount = parseAmount(payment.get("amount"));
                String paymentType = (String) payment.get("type");

                if (paymentAmount > 0 && paymentType != null && !paymentType.isEmpty()) {
                    String date = java.time.LocalDate.now().toString();
                    String desc = "Fee Payment: " + paymentType;

                    // Add to payments table
                    db.update("INSERT INTO payments (id, studentId, type, amount, date, year, description) VALUES (?, ?, ?, ?, ?, ?, ?)",
                            getNextId("payments"), studentId, paymentType, paymentAmount, date, academicYear, desc);

                    // Update total paid in students table
                    db.update("UPDATE students SET paidAmount = paidAmount + ? WHERE collegeId = ?", paymentAmount, studentId);

                    // FIX: Force 'credit' to be lowercase for matching the frontend's case sensitivity check
                    db.update("INSERT INTO daily_transactions (id, date, amount, description, type) VALUES (?, ?, ?, ?, 'credit')",
                            getNextId("daily_transactions"), date, paymentAmount, "Fee Payment: " + studentId + " - " + paymentType);
                }
            }

            return new ApiResponse(true, "Fees and payment updated successfully!", null);
        } catch (Exception e) {
            e.printStackTrace();
            return new ApiResponse(false, "Update Error: " + e.getMessage(), null);
        }
    }

    // --- Edit an Existing Payment Record ---
    @PostMapping("/payment/edit")
    public ApiResponse editPayment(@RequestBody Map<String, Object> req) {
        try {
            int paymentId = Integer.parseInt(req.get("paymentId").toString());
            String originalStudentId = (String) req.get("originalStudentId");
            String newStudentId = (String) req.get("newStudentId");
            String newType = (String) req.get("newType");
            double newAmount = parseAmount(req.get("newAmount"));
            
            // 1. Revert the old amount from the original student's total
            Double oldAmount = db.queryForObject("SELECT amount FROM payments WHERE id = ?", Double.class, paymentId);
            if (oldAmount != null) {
                db.update("UPDATE students SET paidAmount = paidAmount - ? WHERE collegeId = ?", oldAmount, originalStudentId);
            }
            
            // 2. Update the payment record itself
            db.update("UPDATE payments SET studentId = ?, type = ?, amount = ?, description = ? WHERE id = ?",
                    newStudentId, newType, newAmount, "Fee Payment: " + newType, paymentId);
            
            // 3. Add the new amount to the (potentially new) student's total
            db.update("UPDATE students SET paidAmount = paidAmount + ? WHERE collegeId = ?", newAmount, newStudentId);

            return new ApiResponse(true, "Payment updated successfully!", null);
        } catch (Exception e) {
            e.printStackTrace();
            return new ApiResponse(false, "Edit Payment Error: " + e.getMessage(), null);
        }
    }

    // --- 5. Daily Transactions (CATCH-ALL ENDPOINTS) ---
    @PostMapping({"/transactions", "/add-transaction", "/daily-transactions"})
    public ApiResponse addTransaction(@RequestBody Map<String, Object> tx) {
        try {
            // Uses safe number parser
            double amount = parseAmount(tx.get("amount"));
            
            db.update("INSERT INTO daily_transactions (id, date, amount, description, type) VALUES (?, ?, ?, ?, ?)",
                    getNextId("daily_transactions"), tx.get("date"), amount, tx.get("description"), tx.get("type"));
            return new ApiResponse(true, "Transaction added successfully!", null);
        } catch (Exception e) {
            e.printStackTrace();
            return new ApiResponse(false, "Transaction Error: " + e.getMessage(), null);
        }
    }

    @GetMapping("/transactions")
    public ApiResponse getTransactions() {
        // FIX: Force lowercase on all historical transactions directly from the backend, 
        // so the frontend summary numbers will fix themselves instantly.
        List<Map<String, Object>> transactions = db.queryForList("SELECT * FROM daily_transactions ORDER BY date DESC");
        for (Map<String, Object> tx : transactions) {
            if (tx.get("type") != null) {
                tx.put("type", tx.get("type").toString().toLowerCase());
            }
        }
        return new ApiResponse(true, "Success", transactions);
    }

    // --- 6. Management & Department Analytics ---
    @GetMapping("/management/analytics")
    public ApiResponse getAnalytics() {
        Map<String, Object> data = new HashMap<>();
        data.put("monthlyCollections", new ArrayList<>());
        data.put("pendingByDept", new ArrayList<>());
        return new ApiResponse(true, "Success", data);
    }

    @GetMapping("/management/pending-fees")
    public ApiResponse getPendingFees(@RequestParam(defaultValue = "all") String academicYear) {
        String sql = "SELECT u.full_name, u.college_id as studentId, u.course, s.currentAcademicYear, " +
                     "fs.academic_year, fs.fee_type, fs.amount_due FROM users u " +
                     "JOIN students s ON u.college_id = s.collegeId " +
                     "JOIN fee_structures fs ON u.college_id = fs.studentId WHERE u.role = 'student'";
        
        List<Map<String, Object>> rawFees = db.queryForList(sql);
        List<Map<String, Object>> pendingFees = new ArrayList<>();
        
        for (Map<String, Object> row : rawFees) {
            String studentId = (String) row.get("studentId");
            String course = (String) row.get("course");
            
            // Re-calculate the current year dynamically for accurate Passed Out reporting
            row.put("currentAcademicYear", calculateCurrentAcademicYear(studentId, course));
            
            Double paid = db.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM payments WHERE studentId = ? AND type = ? AND year = ?", 
                Double.class, studentId, row.get("fee_type"), row.get("academic_year"));
            double due = ((Number) row.get("amount_due")).doubleValue();
            double pending = due - (paid != null ? paid : 0.0);
            
            if (pending > 0) {
                row.put("total_paid", paid != null ? paid : 0.0);
                row.put("pending_amount", pending);
                row.put("academicYearDisplay", row.get("academic_year") + " Year");
                pendingFees.add(row);
            }
        }
        return new ApiResponse(true, "Success", pendingFees);
    }

    // --- UPDATED DEPARTMENT PENDING FEES LOGIC ---
    @PostMapping("/department/pending-fees")
    public ApiResponse getDeptPendingFees(@RequestBody Map<String, Object> req) {
        String rawCourse = (String) req.get("departmentCourse"); 
        String course = rawCourse != null ? rawCourse.trim().toUpperCase() : ""; 
        
        String sql = "SELECT u.full_name, u.college_id as studentId, u.course, s.currentAcademicYear, " +
                     "fs.academic_year, fs.fee_type, fs.amount_due FROM users u " +
                     "JOIN students s ON u.college_id = s.collegeId " +
                     "JOIN fee_structures fs ON u.college_id = fs.studentId WHERE u.role = 'student'";
                     
        List<Map<String, Object>> rawFees = db.queryForList(sql);
        List<Map<String, Object>> pendingFees = new ArrayList<>();
        
        Map<String, List<String>> deptCodeMap = new HashMap<>();
        deptCodeMap.put("CSE", Arrays.asList("05"));
        deptCodeMap.put("CSD", Arrays.asList("44", "58"));
        deptCodeMap.put("CSM", Arrays.asList("42", "47"));
        deptCodeMap.put("ECE", Arrays.asList("04"));
        deptCodeMap.put("MECHANICAL", Arrays.asList("03", "ME"));
        deptCodeMap.put("EEE", Arrays.asList("02"));
        deptCodeMap.put("CIVIL", Arrays.asList("01"));
        
        List<String> shCodes = Arrays.asList("05", "44", "58", "42", "47", "04");
        
        for (Map<String, Object> row : rawFees) {
            String studentId = (String) row.get("studentId");
            String studentCourse = (String) row.get("course");
            
            // Use Dynamic Chronological Logic for correct Passed Out detection
            int currentYear = calculateCurrentAcademicYear(studentId, studentCourse);
            row.put("currentAcademicYear", currentYear);
            
            int feeYear = 1;
            if (row.get("academic_year") != null) {
                feeYear = Integer.parseInt(row.get("academic_year").toString());
            }
            
            String cleanId = studentId != null ? studentId.trim().toUpperCase() : "";
            
            String deptCode = "";
            if (cleanId.length() >= 4) {
                deptCode = cleanId.substring(cleanId.length() - 4, cleanId.length() - 2);
            } else if (cleanId.length() >= 2) {
                deptCode = cleanId.substring(0, 2); 
            }
            
            boolean belongsToDepartment = false;
            
            if (course.isEmpty() || course.equalsIgnoreCase("ALL")) {
                belongsToDepartment = true;
            } else if ("S&H".equals(course)) {
                // S&H Strict Filter: Only 1st year students AND 1st year fees
                belongsToDepartment = shCodes.contains(deptCode) && currentYear == 1 && feeYear == 1;
            } else if ("DIPLOMA".equals(course)) {
                belongsToDepartment = (studentCourse != null && studentCourse.trim().equalsIgnoreCase("DIPLOMA"));
            } else {
                List<String> validCodes = deptCodeMap.get(course);
                if (validCodes != null && validCodes.contains(deptCode)) {
                    belongsToDepartment = true; 
                } else if (studentCourse != null && studentCourse.trim().equalsIgnoreCase(course)) {
                    belongsToDepartment = true;
                }
            }
            
            if (!belongsToDepartment) {
                continue;
            }
            
            Double paid = db.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM payments WHERE studentId = ? AND type = ? AND year = ?", 
                Double.class, studentId, row.get("fee_type"), row.get("academic_year"));
            double due = ((Number) row.get("amount_due")).doubleValue();
            double pending = due - (paid != null ? paid : 0.0);
            
            if (pending > 0) {
                row.put("total_paid", paid != null ? paid : 0.0);
                row.put("pending_amount", pending);
                row.put("academicYearDisplay", row.get("academic_year") + " Year");
                pendingFees.add(row);
            }
        }
        return new ApiResponse(true, "Success", pendingFees);
    }

    // --- 7. Scheduled Cron Job ---
    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Kolkata")
    public void sendFeeReminders() {
        try {
            String sql = "SELECT u.college_id, u.full_name, u.email, s.dueDate, s.paidAmount, " +
                         "(SELECT SUM(fs.amount_due) FROM fee_structures fs WHERE fs.studentId = u.college_id) as totalDue " +
                         "FROM users u JOIN students s ON u.college_id = s.collegeId " +
                         "WHERE s.dueDate <= DATE_ADD(CURDATE(), INTERVAL 7 DAY) AND s.dueDate >= CURDATE()";
            
            List<Map<String, Object>> students = db.queryForList(sql);
            for (Map<String, Object> st : students) {
                double totalDue = st.get("totalDue") != null ? ((Number) st.get("totalDue")).doubleValue() : 0;
                double paid = st.get("paidAmount") != null ? ((Number) st.get("paidAmount")).doubleValue() : 0;
                if ((totalDue - paid) > 0 && st.get("email") != null) {
                    sendEmail((String) st.get("email"), "Fee Reminder", "Dear " + st.get("full_name") + ", you have pending fees.");
                }
            }
        } catch (Exception e) {
            System.out.println("Cron job failed: " + e.getMessage());
        }
    }

    private void sendEmail(String to, String subject, String text) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(emailFrom);
        msg.setTo(to);
        msg.setSubject(subject);
        msg.setText(text);
        mailSender.send(msg);
    }
}