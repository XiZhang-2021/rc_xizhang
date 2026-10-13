package xizhang.rc_xizhang_coding.client;

import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import xizhang.rc_xizhang_coding.config.NotificationProperties;
import xizhang.rc_xizhang_coding.dto.RequestData;
import xizhang.rc_xizhang_coding.entity.NotificationTask;

@Component
public class SupplierClient {
    public enum Outcome { SUCCESS, RETRY, DUPLICATE, FAILED }
    private final HttpClient client;
    private final JsonMapper json;
    private final NotificationProperties properties;

    public SupplierClient(JsonMapper json, NotificationProperties properties) {
        this.json = json;
        this.properties = properties;
        this.client = HttpClient.newBuilder().connectTimeout(properties.httpTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Outcome send(NotificationTask task, RequestData data) throws InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(data.url()))
                .timeout(properties.httpTimeout());
        data.headers().forEach(builder::header);
        builder.header("X-Task-Id", task.getTaskId()).header("X-Request-Id", task.getRequestId());
        var future = client.sendAsync(builder.method(data.method(), HttpRequest.BodyPublishers.ofString(data.body()))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
        try {
            // Bound the entire exchange, including response body delivery.
            var response = future.get(properties.httpTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response.body().length > 65536) return Outcome.FAILED;
            return classify(response.statusCode(), response.body());
        } catch (TimeoutException | ExecutionException e) {
            future.cancel(true);
            return Outcome.RETRY;
        } catch (InterruptedException e) {
            future.cancel(true);
            throw e;
        }
    }

    Outcome classify(int status, byte[] body) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject()) return Outcome.FAILED;
            for (String key : new String[]{"retryable", "repeatRequest", "success"}) {
                if (!root.has(key) || !root.get(key).isBoolean()) return Outcome.FAILED;
            }
            boolean retry = root.get("retryable").booleanValue();
            boolean duplicate = root.get("repeatRequest").booleanValue();
            boolean success = root.get("success").booleanValue();
            if ((retry ? 1 : 0) + (duplicate ? 1 : 0) + (success ? 1 : 0) > 1) return Outcome.FAILED;
            if (success) return status >= 200 && status < 300 ? Outcome.SUCCESS : Outcome.FAILED;
            if (duplicate) return Outcome.DUPLICATE;
            if (retry) return Outcome.RETRY;
            return Outcome.FAILED;
        } catch (RuntimeException e) {
            return Outcome.FAILED;
        }
    }
}
