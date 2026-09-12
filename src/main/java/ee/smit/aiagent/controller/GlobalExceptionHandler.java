package ee.smit.aiagent.controller;

import ee.smit.aiagent.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    static final String CORRELATION_ID_MDC_KEY = "correlationId";

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage() != null ? err.getDefaultMessage() : err.getField() + " is invalid")
                .orElse("Validation failed");

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, "Bad Request", message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(400, "Bad Request", "Malformed JSON request body"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String reason = status.getReasonPhrase();

        if (isInternalServerFailure(status)) {
            String correlationId = resolveCorrelationId();
            log.error("Internal request failure status={} correlationId={} reason={}",
                    status.value(), correlationId, ex.getReason(), ex);
            String message = publicMessageForInternalFailure(status);
            clearCorrelationId();
            return ResponseEntity
                    .status(status)
                    .body(new ErrorResponse(status.value(), reason, message, correlationId));
        }

        String message = ex.getReason() != null ? ex.getReason() : reason;
        return ResponseEntity
                .status(status)
                .body(new ErrorResponse(status.value(), reason, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        String correlationId = resolveCorrelationId();
        log.error("Unhandled error correlationId={}", correlationId, ex);
        clearCorrelationId();
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "Internal Server Error", "Unexpected server error", correlationId));
    }

    private static boolean isInternalServerFailure(HttpStatus status) {
        return status == HttpStatus.INTERNAL_SERVER_ERROR || status == HttpStatus.BAD_GATEWAY;
    }

    private static String publicMessageForInternalFailure(HttpStatus status) {
        if (status == HttpStatus.BAD_GATEWAY) {
            return "AI provider request failed";
        }
        return "Unexpected server error";
    }

    private static String resolveCorrelationId() {
        String existing = MDC.get(CORRELATION_ID_MDC_KEY);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        return UUID.randomUUID().toString();
    }

    private static void clearCorrelationId() {
        MDC.remove(CORRELATION_ID_MDC_KEY);
    }
}
