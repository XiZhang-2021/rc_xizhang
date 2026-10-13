package xizhang.rc_xizhang_coding.validation;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import xizhang.rc_xizhang_coding.dto.RequestData;
import xizhang.rc_xizhang_coding.dto.SubmitRequest;
import xizhang.rc_xizhang_coding.support.TestFixtures;

import static org.assertj.core.api.Assertions.*;

class RequestPolicyTest {
    RequestPolicy policy = new RequestPolicy(TestFixtures.properties("https://supplier.example.com"));

    @Test void canonicalHeadersDoNotChangeBodyOrContentType() {
        var data = policy.validate(new SubmitRequest("r1", "https://supplier.example.com:443/path?a=1", "post",
                Map.of("Content-Type", "application/xml"), "<item> 1 </item>"));
        assertThat(data.method()).isEqualTo("POST");
        assertThat(data.headers()).containsEntry("content-type", "application/xml");
        assertThat(data.body()).isEqualTo("<item> 1 </item>");
    }

    @Test void rejectsUnapprovedOriginsUserInfoAndReservedHeaders() {
        for (String url : new String[]{"http://supplier.example.com/a", "https://supplier.example.com:444/a",
                "https://supplier.example.com.evil.test/a", "https://user@supplier.example.com/a", "file:///etc/passwd"}) {
            assertThatThrownBy(() -> policy.validate(new SubmitRequest("r1", url, "POST", Map.of(), "")))
                    .isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> policy.validate(new SubmitRequest("r1", "https://supplier.example.com/a",
                "POST", Map.of("X-Task-Id", "injected"), ""))).isInstanceOf(ResponseStatusException.class);
    }

    @Test void callbackKeyMustBelongToTargetOrigin() {
        var data = new RequestData("https://supplier.example.com/path", "POST", Map.of(), "");
        assertThatCode(() -> policy.checkCallback(data, "callback-test")).doesNotThrowAnyException();
        assertThatThrownBy(() -> policy.checkCallback(data, "wrong")).isInstanceOf(ResponseStatusException.class);
    }
}
