import com.sun.net.httpserver.HttpExchange;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class HttpServerConfig {
    private static final int DEFAULT_PORT = 8081;
    private static final Set<String> DEVELOPMENT_ORIGINS = new HashSet<>(Arrays.asList(
            "http://localhost:5500",
            "http://127.0.0.1:5500",
            "http://localhost:8080",
            "http://127.0.0.1:8080",
            "http://localhost:8000",
            "http://127.0.0.1:8000"));

    private HttpServerConfig() {
    }

    static int portFromEnvironment() {
        String configuredPort = System.getenv("PORT");
        if (configuredPort == null || configuredPort.trim().isEmpty()) {
            return DEFAULT_PORT;
        }

        try {
            int port = Integer.parseInt(configuredPort.trim());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException("outside valid port range");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    "PORT must be an integer between 1 and 65535.", exception);
        }
    }

    static void addCorsHeaders(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && isAllowedOrigin(origin)) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            exchange.getResponseHeaders().set(
                    "Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            exchange.getResponseHeaders().set(
                    "Access-Control-Allow-Headers", "Content-Type");
        }
        exchange.getResponseHeaders().set("Vary", "Origin");
    }

    private static boolean isAllowedOrigin(String origin) {
        String deployedOrigin = System.getenv("FRONTEND_ORIGIN");
        if (deployedOrigin != null && !deployedOrigin.trim().isEmpty()) {
            return deployedOrigin.trim().equals(origin);
        }
        return DEVELOPMENT_ORIGINS.contains(origin);
    }
}
