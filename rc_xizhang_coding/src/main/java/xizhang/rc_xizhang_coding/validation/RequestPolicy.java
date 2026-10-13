package xizhang.rc_xizhang_coding.validation;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import xizhang.rc_xizhang_coding.config.NotificationProperties;
import xizhang.rc_xizhang_coding.dto.RequestData;
import xizhang.rc_xizhang_coding.dto.SubmitRequest;

@Component
public class RequestPolicy {
    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Set<String> RESERVED = Set.of("x-task-id", "x-request-id", "host", "content-length",
            "connection", "expect", "upgrade", "transfer-encoding", "trailer", "te", "proxy-authorization");
    private final Map<String, String> callbackKeys;

    public RequestPolicy(NotificationProperties properties) {
        Map<String, String> keys = new HashMap<>();
        properties.suppliers().values().forEach(supplier -> {
            URI uri = URI.create(supplier.origin());
            String origin = origin(uri);
            if (uri.getRawQuery() != null || (uri.getRawPath() != null
                    && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))) {
                throw new IllegalArgumentException("Supplier origin must not include a path or query");
            }
            if (keys.putIfAbsent(origin, supplier.callbackKey()) != null) {
                throw new IllegalArgumentException("Duplicate supplier origin");
            }
        });
        callbackKeys = Map.copyOf(keys);
    }

    public RequestData validate(SubmitRequest input) {
        if (input == null || input.requestId() == null
                || !input.requestId().matches("[A-Za-z0-9._:-]{1,128}")) {
            throw badRequest("requestId must be 1..128 ASCII letters, digits or ._:-");
        }
        try {
            URI uri = URI.create(input.url());
            if (!callbackKeys.containsKey(origin(uri))) throw badRequest("Target origin is not allowed");
            String method = input.method() == null ? "" : input.method().toUpperCase(Locale.ROOT);
            if (!METHODS.contains(method)) throw badRequest("Unsupported HTTP method");
            String body = input.body() == null ? "" : input.body();
            if (body.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) throw badRequest("Body exceeds 1 MiB");
            Map<String, String> headers = new TreeMap<>();
            HttpRequest.Builder check = HttpRequest.newBuilder(uri);
            if (input.headers() != null) {
                for (var header : input.headers().entrySet()) {
                    String name = header.getKey().toLowerCase(Locale.ROOT);
                    if (RESERVED.contains(name)) throw badRequest("Reserved HTTP header: " + name);
                    if (headers.putIfAbsent(name, header.getValue()) != null) throw badRequest("Duplicate header name");
                    check.header(name, header.getValue());
                }
            }
            check.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
            return new RequestData(uri.toASCIIString(), method, Map.copyOf(headers), body);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw badRequest("Invalid request URL or headers");
        }
    }

    public void checkTarget(RequestData data) {
        if (!callbackKeys.containsKey(origin(URI.create(data.url())))) throw badRequest("Target origin is not allowed");
    }

    public void checkCallback(RequestData data, String key) {
        String expected = callbackKeys.get(origin(URI.create(data.url())));
        if (!sameSecret(expected, key)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid callback key");
    }

    public static boolean sameSecret(String expected, String actual) {
        return expected != null && actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!Set.of("http", "https").contains(scheme) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Invalid HTTP origin");
        }
        int port = uri.getPort() == -1 ? (scheme.equals("https") ? 443 : 80) : uri.getPort();
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
