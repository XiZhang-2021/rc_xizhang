package xizhang.rc_xizhang_coding.client;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import xizhang.rc_xizhang_coding.support.TestFixtures;

import static org.assertj.core.api.Assertions.*;
import static xizhang.rc_xizhang_coding.client.SupplierClient.Outcome.*;

class SupplierClientTest {
    SupplierClient client = new SupplierClient(new JsonMapper(), TestFixtures.properties("http://localhost:9090"));

    @Test void checksAllEightFlagCombinations() {
        for (int flags = 0; flags < 8; flags++) {
            boolean retry = (flags & 4) != 0, duplicate = (flags & 2) != 0, success = (flags & 1) != 0;
            var expected = switch (flags) { case 1 -> SUCCESS; case 2 -> DUPLICATE; case 4 -> RETRY; default -> FAILED; };
            String body = "{\"retryable\":" + retry + ",\"repeatRequest\":" + duplicate + ",\"success\":" + success + "}";
            assertThat(client.classify(200, body.getBytes(StandardCharsets.UTF_8))).isEqualTo(expected);
        }
    }

    @Test void missingOrNonBooleanFlagsAndHttpFailureCannotMeanSuccess() {
        for (String body : new String[]{"{}", "null", "garbage", "{\"success\":true}",
                "{\"retryable\":\"false\",\"repeatRequest\":false,\"success\":true}"}) {
            assertThat(client.classify(200, body.getBytes(StandardCharsets.UTF_8))).isEqualTo(FAILED);
        }
        assertThat(client.classify(500,
                "{\"retryable\":false,\"repeatRequest\":false,\"success\":true}".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(FAILED);
    }
}
