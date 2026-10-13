package xizhang.rc_xizhang_coding.exception;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> status(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("error",
                e.getReason() == null ? "Request rejected" : e.getReason()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, String>> malformed() {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid JSON request"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, String>> database() {
        return ResponseEntity.status(503).body(Map.of("error", "Database temporarily unavailable"));
    }
}
