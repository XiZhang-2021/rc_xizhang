package xizhang.rc_xizhang_coding.dto;

import java.util.Map;

public record RequestData(String url, String method, Map<String, String> headers, String body) {}
