package xizhang.rc_xizhang_coding.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("notification")
public record NotificationProperties(String apiKey, Map<String, Supplier> suppliers,
        Duration httpTimeout, int batchSize, int workers, int queueCapacity, boolean schedulerEnabled) {
    public NotificationProperties {
        if (apiKey == null || apiKey.isBlank() || suppliers == null || suppliers.isEmpty()) {
            throw new IllegalArgumentException("Configure an API key and at least one supplier");
        }
        if (httpTimeout == null || httpTimeout.isNegative() || httpTimeout.isZero()
                || workers < 1 || workers > 4 || batchSize < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("Invalid notification worker settings (workers: 1..4)");
        }
        suppliers = Map.copyOf(suppliers);
    }

    public record Supplier(String origin, String callbackKey) {
        public Supplier {
            if (origin == null || callbackKey == null || callbackKey.isBlank()) {
                throw new IllegalArgumentException("Each supplier needs origin and callback-key");
            }
        }
    }
}
