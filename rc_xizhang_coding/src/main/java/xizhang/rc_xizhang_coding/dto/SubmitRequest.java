package xizhang.rc_xizhang_coding.dto;

import java.util.Map;

public record SubmitRequest(String requestId, String url, String method,
                            Map<String, String> headers, String body) {}
