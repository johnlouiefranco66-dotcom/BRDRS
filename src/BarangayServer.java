import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class BarangayServer {
    private static final String DB_URL = System.getenv("DB_URL") != null
        ? System.getenv("DB_URL") : "jdbc:mysql://127.0.0.1:3306/brdrs_db";
    private static final String DB_USER = System.getenv("DB_USER") != null
        ? System.getenv("DB_USER") : "root";
    private static final String DB_PASS = System.getenv("DB_PASS") != null
        ? System.getenv("DB_PASS") : "";

    public static void main(String[] args) throws Exception {
        int port = System.getenv("PORT") != null ? Integer.parseInt(System.getenv("PORT")) : 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/", exchange -> {
            byte[] response = Files.readAllBytes(Paths.get("web/index.html"));
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });

        server.createContext("/api/register", BarangayServer::handleRegister);
        server.createContext("/api/login", BarangayServer::handleLogin);
        server.createContext("/api/submit", BarangayServer::handleSubmit);
        server.createContext("/api/logs", BarangayServer::handleLogs);
        server.createContext("/api/file", BarangayServer::handleViewFile);
        server.createContext("/api/approve", BarangayServer::handleApprove);

        server.setExecutor(null);
        System.out.println("=========================================");
        System.out.println("BRDRS Server running on port " + port);
        System.out.println("=========================================");
        server.start();
    }

    private static void handleRegister(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseFormData(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String sql = "INSERT INTO users (full_name, email, password, address, role) VALUES (?, ?, ?, ?, 'resident')";

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, params.get("fullName"));
            stmt.setString(2, params.get("email"));
            stmt.setString(3, params.get("password"));
            stmt.setString(4, params.get("address"));
            stmt.executeUpdate();
            sendJson(exchange, "{\"status\":\"success\",\"message\":\"Account created! You can now sign in.\"}");
        } catch (SQLException e) {
            sendJson(exchange, "{\"status\":\"error\",\"message\":\"Email already exists or database error.\"}");
        }
    }

    private static void handleLogin(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseFormData(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String sql = "SELECT user_id, full_name, email, address, role FROM users WHERE email = ? AND password = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, params.get("email"));
            stmt.setString(2, params.get("password"));
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                String json = String.format(
                    "{\"status\":\"success\",\"user\":{\"userId\":%d,\"fullName\":\"%s\",\"email\":\"%s\",\"address\":\"%s\",\"role\":\"%s\"}}",
                    rs.getInt("user_id"),
                    escapeJson(rs.getString("full_name")),
                    escapeJson(rs.getString("email")),
                    escapeJson(rs.getString("address")),
                    escapeJson(rs.getString("role"))
                );
                sendJson(exchange, json);
            } else {
                sendJson(exchange, "{\"status\":\"error\",\"message\":\"Invalid email or password.\"}");
            }
        } catch (SQLException e) {
            sendJson(exchange, "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static void handleSubmit(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseFormData(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

        int userId = Integer.parseInt(params.get("userId"));
        String name = params.get("residentName");
        String address = params.get("address");
        String docType = params.get("documentType");
        String reqChecked = params.getOrDefault("requirementsChecked", "Verified");
        String fileName = params.get("uploadedFileName");
        String fileData = params.get("uploadedFileData");
        boolean isSeniorPwd = Boolean.parseBoolean(params.get("isSeniorPwd"));
        double baseFee = Double.parseDouble(params.get("baseFee"));
        double totalFee = Double.parseDouble(params.get("totalFee"));
        String paymentMethod = params.get("paymentMethod");
        String bankProvider = params.get("bankProvider");
        String referenceNo = params.get("referenceNo");
        double cashTendered = Double.parseDouble(params.get("cashTendered"));

        String cedulaNo = emptyToNull(params.get("cedulaNo"));
        BigDecimal monthlyIncome = parseDecimalOrNull(params.get("monthlyIncome"));
        Integer yearsResiding = parseIntOrNull(params.get("yearsResiding"));
        String purokSitio = emptyToNull(params.get("purokSitio"));
        String businessName = emptyToNull(params.get("businessName"));
        String businessNature = emptyToNull(params.get("businessNature"));
        String businessAddress = emptyToNull(params.get("businessAddress"));

        double discount = baseFee - totalFee;
        double change = cashTendered - totalFee;

        String sql = "INSERT INTO document_requests (" +
            "user_id, resident_name, address, document_type, requirements_checked, " +
            "uploaded_file_name, uploaded_file_data, is_senior_pwd, base_fee, discount, " +
            "total_fee, payment_method, bank_provider, reference_no, cash_tendered, change_amount, " +
            "cedula_no, monthly_income, years_residing, purok_sitio, " +
            "business_name, business_nature, business_address" +
            ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setInt(1, userId);
            stmt.setString(2, name);
            stmt.setString(3, address);
            stmt.setString(4, docType);
            stmt.setString(5, reqChecked);
            stmt.setString(6, fileName);
            stmt.setString(7, fileData);
            stmt.setBoolean(8, isSeniorPwd);
            stmt.setDouble(9, baseFee);
            stmt.setDouble(10, discount);
            stmt.setDouble(11, totalFee);
            stmt.setString(12, paymentMethod);
            stmt.setString(13, bankProvider);
            stmt.setString(14, referenceNo);
            stmt.setDouble(15, cashTendered);
            stmt.setDouble(16, change);

            stmt.setString(17, cedulaNo);
            if (monthlyIncome != null) {
                stmt.setBigDecimal(18, monthlyIncome);
            } else {
                stmt.setNull(18, Types.DECIMAL);
            }
            if (yearsResiding != null) {
                stmt.setInt(19, yearsResiding);
            } else {
                stmt.setNull(19, Types.INTEGER);
            }
            stmt.setString(20, purokSitio);
            stmt.setString(21, businessName);
            stmt.setString(22, businessNature);
            stmt.setString(23, businessAddress);

            stmt.executeUpdate();

            ResultSet rs = stmt.getGeneratedKeys();
            int generatedId = rs.next() ? rs.getInt(1) : 0;

            String json = String.format(
                "{\"status\":\"success\",\"id\":%d,\"residentName\":\"%s\",\"documentType\":\"%s\",\"paymentMethod\":\"%s\",\"bankProvider\":\"%s\",\"referenceNo\":\"%s\",\"totalFee\":\"%.2f\",\"change\":\"%.2f\"}",
                generatedId, escapeJson(name), escapeJson(docType), escapeJson(paymentMethod), escapeJson(bankProvider), escapeJson(referenceNo), totalFee, change
            );
            sendJson(exchange, json);
        } catch (SQLException e) {
            e.printStackTrace();
            sendJson(exchange, "{\"status\":\"error\",\"message\":\"" + escapeJson(e.getMessage()) + "\"}");
        }
    }

    private static void handleLogs(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseFormData(exchange.getRequestURI().getRawQuery());
        String role = query.getOrDefault("role", "resident");
        int userId = Integer.parseInt(query.getOrDefault("userId", "0"));

        String sql = "admin".equals(role)
            ? "SELECT request_id, resident_name, document_type, requirements_checked, payment_method, bank_provider, reference_no, total_fee, status, request_date, cedula_no, monthly_income, years_residing, purok_sitio, business_name, business_nature, business_address FROM document_requests ORDER BY request_id DESC"
            : "SELECT request_id, resident_name, document_type, requirements_checked, payment_method, bank_provider, reference_no, total_fee, status, request_date, cedula_no, monthly_income, years_residing, purok_sitio, business_name, business_nature, business_address FROM document_requests WHERE user_id = ? ORDER BY request_id DESC";

        StringBuilder json = new StringBuilder("[");
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (!"admin".equals(role)) stmt.setInt(1, userId);
            ResultSet rs = stmt.executeQuery();

            boolean first = true;
            while (rs.next()) {
                if (!first) json.append(",");
                BigDecimal income = rs.getBigDecimal("monthly_income");
                int years = rs.getInt("years_residing");
                String yearsStr = rs.wasNull() ? "" : String.valueOf(years);

                json.append(String.format(
                    "{\"id\":%d,\"name\":\"%s\",\"doc\":\"%s\",\"checkedDocs\":\"%s\",\"paymentMethod\":\"%s\",\"bankProvider\":\"%s\",\"referenceNo\":\"%s\",\"total\":\"%.2f\",\"status\":\"%s\",\"date\":\"%s\",\"cedulaNo\":\"%s\",\"monthlyIncome\":\"%s\",\"yearsResiding\":\"%s\",\"purokSitio\":\"%s\",\"businessName\":\"%s\",\"businessNature\":\"%s\",\"businessAddress\":\"%s\"}",
                    rs.getInt("request_id"),
                    escapeJson(rs.getString("resident_name")),
                    escapeJson(rs.getString("document_type")),
                    escapeJson(rs.getString("requirements_checked")),
                    escapeJson(rs.getString("payment_method")),
                    escapeJson(rs.getString("bank_provider")),
                    escapeJson(rs.getString("reference_no")),
                    rs.getDouble("total_fee"),
                    escapeJson(rs.getString("status")),
                    escapeJson(rs.getString("request_date")),
                    escapeJson(rs.getString("cedula_no")),
                    escapeJson(income != null ? income.toPlainString() : ""),
                    escapeJson(yearsStr),
                    escapeJson(rs.getString("purok_sitio")),
                    escapeJson(rs.getString("business_name")),
                    escapeJson(rs.getString("business_nature")),
                    escapeJson(rs.getString("business_address"))
                ));
                first = false;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        json.append("]");
        sendJson(exchange, json.toString());
    }

    private static void handleViewFile(HttpExchange exchange) throws IOException {
        Map<String, String> query = parseFormData(exchange.getRequestURI().getRawQuery());
        int id = Integer.parseInt(query.getOrDefault("id", "0"));
        String sql = "SELECT uploaded_file_name, uploaded_file_data FROM document_requests WHERE request_id = ?";

        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String json = String.format("{\"fileName\":\"%s\",\"fileData\":\"%s\"}",
                    escapeJson(rs.getString("uploaded_file_name")),
                    escapeJson(rs.getString("uploaded_file_data") == null ? "" : rs.getString("uploaded_file_data"))
                );
                sendJson(exchange, json);
                return;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        sendJson(exchange, "{\"fileName\":\"Not Found\",\"fileData\":\"\"}");
    }

    private static void handleApprove(HttpExchange exchange) throws IOException {
        Map<String, String> params = parseFormData(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        int id = Integer.parseInt(params.get("id"));
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
             PreparedStatement stmt = conn.prepareStatement("UPDATE document_requests SET status = 'Approved' WHERE request_id = ?")) {
            stmt.setInt(1, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        sendJson(exchange, "{\"status\":\"success\"}");
    }

    private static String emptyToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static BigDecimal parseDecimalOrNull(String s) {
        String clean = emptyToNull(s);
        if (clean == null) return null;
        try {
            return new BigDecimal(clean.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer parseIntOrNull(String s) {
        String clean = emptyToNull(s);
        if (clean == null) return null;
        try {
            return Integer.parseInt(clean.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void sendJson(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static Map<String, String> parseFormData(String formData) {
        Map<String, String> map = new HashMap<>();
        if (formData == null || formData.isEmpty()) return map;
        for (String pair : formData.split("&")) {
            String[] keyValue = pair.split("=", 2);
            String key = URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8);
            String value = keyValue.length > 1 ? URLDecoder.decode(keyValue[1], StandardCharsets.UTF_8) : "";
            map.put(key, value);
        }
        return map;
    }
}
