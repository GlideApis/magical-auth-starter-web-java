package io.glideapis.magicalauth;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.glideapi.GlideClient;
import com.glideapi.services.MagicAuthClient;

import io.github.cdimascio.dotenv.Dotenv;
import jakarta.servlet.http.HttpServletRequest;

@RestController
public class MagicalController {

    private static Dotenv dotenv;
    private final GlideClient glideClient;
    private final Map<String, Map<String, Object>> stateCache = new HashMap<>();
    private final String PORT;
    private final String redirectUri;

    public MagicalController() {
        System.out.println("Initializing MagicalController...");
        
        // Get environment variables with system env first
        String clientId = System.getenv("GLIDE_CLIENT_ID");
        String clientSecret = System.getenv("GLIDE_CLIENT_SECRET");
        this.PORT = System.getenv().getOrDefault("PORT", "8080");
        this.redirectUri = System.getenv("MAGIC_REDIRECT_URI");

        System.out.println("Environment variables from system:");
        System.out.println("PORT=" + this.PORT);
        System.out.println("MAGIC_REDIRECT_URI exists: " + (redirectUri != null));
        System.out.println("GLIDE_CLIENT_ID exists: " + (clientId != null));
        System.out.println("GLIDE_CLIENT_SECRET exists: " + (clientSecret != null));

        if (clientId == null || clientSecret == null) {
            // Fallback to .env file for local development
            try {
                System.out.println("Attempting to load .env file...");
                dotenv = Dotenv.load();
                clientId = clientId != null ? clientId : dotenv.get("GLIDE_CLIENT_ID");
                clientSecret = clientSecret != null ? clientSecret : dotenv.get("GLIDE_CLIENT_SECRET");
                System.out.println(".env file loaded successfully");
            } catch (Exception e) {
                System.out.println("Error loading .env file: " + e.getMessage());
                throw new RuntimeException("Missing required environment variables: GLIDE_CLIENT_ID and GLIDE_CLIENT_SECRET must be set");
            }
        }

        this.glideClient = new GlideClient(clientId, clientSecret);
        System.out.println("MagicalController initialized successfully");
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            // If multiple IPs in X-Forwarded-For, take the first one (original client)
            ip = ip.split(",")[0].strip();
        }
        return ip;
    }

    private String getRedirectUri() {
        if (redirectUri != null) {
            return redirectUri;
        }
        if (dotenv != null) {
            return dotenv.get("MAGIC_REDIRECT_URI", "http://localhost:" + PORT + "/");
        }
        return "http://localhost:" + PORT + "/";
    }

    @PostMapping("/api/start-verification")
    public ResponseEntity<?> startVerification(@RequestBody Map<String, String> body) {
        String phoneNumber = body.get("phoneNumber");
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String deviceIpAddress = getClientIp(request);
        
        System.out.println("Start Auth for " + phoneNumber + " from IP " + deviceIpAddress);
        
        try {
            String sessionId = UUID.randomUUID().toString();
            Map<String, Object> sessionData = new HashMap<>();
            sessionData.put("phoneNumber", phoneNumber);
            sessionData.put("status", "pending");
            sessionData.put("deviceIpAddress", deviceIpAddress);
            stateCache.put(sessionId, sessionData);
            
            MagicAuthClient.BaseMagicAuthStartProps authInput = new MagicAuthClient.BaseMagicAuthStartProps();
            authInput.state = sessionId;
            authInput.phoneNumber = phoneNumber;
            authInput.redirectUrl = getRedirectUri();
            authInput.deviceIpAddress = deviceIpAddress;

            System.out.println("Starting auth with redirectUrl: " + authInput.redirectUrl);
            MagicAuthClient.MagicAuthStartResponse authRes = glideClient.magicAuth.startAuth(authInput, null);
            return ResponseEntity.ok(authRes);
        } catch (Exception e) {
            System.out.println("Error in startVerification: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", "Error starting auth"));
        }
    }

    @PostMapping("/api/check-verification")
    public ResponseEntity<?> checkVerification(@RequestBody Map<String, String> body) {
        String phoneNumber = body.get("phoneNumber");
        String token = body.get("token");
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        String deviceIpAddress = getClientIp(request);
        
        System.out.println("Check Auth for " + phoneNumber + " from IP " + deviceIpAddress);
        
        try {
            MagicAuthClient.MagicAuthVerifyProps verifyProps = new MagicAuthClient.MagicAuthVerifyProps();
            verifyProps.phoneNumber = phoneNumber;
            verifyProps.token = token;
            verifyProps.deviceIpAddress = deviceIpAddress;
            
            MagicAuthClient.MagicAuthCheckResponse checkRes = glideClient.magicAuth.verifyAuth(verifyProps, null);
            
            // Update session status if verification successful
            stateCache.forEach((sessionId, sessionData) -> {
                if (phoneNumber.equals(sessionData.get("phoneNumber"))) {
                    sessionData.put("status", checkRes.verified ? "verified" : "failed");
                }
            });
            
            return ResponseEntity.ok(checkRes);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", "Error verifying token"));
        }
    }

    @PostMapping("/api/get-session")
    public ResponseEntity<?> getSession(@RequestBody Map<String, String> body) {
        String state = body.get("state");
        System.out.println("Get Session");
        try {
            Map<String, Object> sessionData = stateCache.get(state);
            if (sessionData == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Session not found"));
            }
            return ResponseEntity.ok(Map.of(
                "phoneNumber", sessionData.get("phoneNumber"),
                "status", sessionData.get("status")
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.badRequest().body(Map.of("error", "Error getting session"));
        }
    }
}
