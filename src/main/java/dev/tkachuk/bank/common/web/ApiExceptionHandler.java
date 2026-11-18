package dev.tkachuk.bank.common.web;

import dev.tkachuk.bank.common.error.BankException;
import dev.tkachuk.bank.common.error.ErrorCode;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 9457 problem details with a "code" field.
 * The ErrorCode to HTTP status mapping lives only here, so business code never deals with HTTP.
 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";
    /** Words of an error code that a title writes in capitals. */
    private static final Set<String> ACRONYMS = Set.of("iban");

    @ExceptionHandler(BankException.class)
    ResponseEntity<ProblemDetail> handleBankException(BankException ex) {
        HttpStatusCode status = statusOf(ex.code());
        ProblemDetail problem = problem(status, ex.code().name(), ex.getMessage());
        if (!ex.violations().isEmpty()) {
            problem.setProperty("violations", ex.violations());
        }
        return ResponseEntity.status(status).body(problem);
    }

    /**
     * A path variable or header broke a constraint of the generated interfaces (@Size on {iban},
     * on Idempotency-Key). Those interfaces are @Validated, so the check runs in a proxy and fails this way.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex) {
        List<String> violations = ex.getConstraintViolations().stream()
                .map(violation -> leafName(violation.getPropertyPath()) + ": " + violation.getMessage())
                .sorted()
                .toList();
        HttpStatusCode status = HttpStatusCode.valueOf(400);
        ProblemDetail problem = problem(status, VALIDATION_FAILED, "The request is invalid");
        problem.setProperty("violations", violations);
        return ResponseEntity.status(status).body(problem);
    }

    /** A database constraint or lock stopped the request, usually two requests racing for the same rows. */
    @ExceptionHandler({DataIntegrityViolationException.class, PessimisticLockingFailureException.class})
    ResponseEntity<ProblemDetail> handleConflict(RuntimeException ex) {
        log.warn("Request stopped by a database constraint or lock: {}", ex.getMessage());
        HttpStatusCode status = statusOf(ErrorCode.CONFLICT);
        ProblemDetail problem = problem(status, ErrorCode.CONFLICT.name(),
                "The request conflicts with another one in progress; retry it");
        return ResponseEntity.status(status).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        ProblemDetail problem = problem(status, VALIDATION_FAILED, "The request body is invalid");
        problem.setProperty("violations", violations);
        return ResponseEntity.status(status).headers(headers).body(problem);
    }

    /** Anything else is a bug: log it with its stack trace and answer in the same problem format, without internals. */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        HttpStatusCode status = HttpStatusCode.valueOf(500);
        ProblemDetail problem = problem(status, INTERNAL_ERROR,
                "Unexpected error; quote the X-Request-Id response header when reporting it");
        return ResponseEntity.status(status).body(problem);
    }

    static HttpStatusCode statusOf(ErrorCode code) {
        int status = switch (code) {
            case ACCOUNT_NOT_FOUND, TRANSFER_NOT_FOUND -> 404;
            case ACCOUNT_ALREADY_EXISTS, IDEMPOTENCY_KEY_REUSED, CONFLICT -> 409;
            case INVALID_IBAN, INVALID_AMOUNT, INVALID_PAYMENT_FILE, BENEFICIARY_NAME_REQUIRED -> 400;
            case UNSUPPORTED_FILE_FORMAT -> 415;
            case SAME_ACCOUNT, CURRENCY_MISMATCH, INSUFFICIENT_FUNDS, UNSUPPORTED_DESTINATION -> 422;
        };
        return HttpStatusCode.valueOf(status);
    }

    /** "createTransfer.idempotencyKey" becomes "idempotencyKey": the caller knows no Java method names. */
    private static String leafName(Path path) {
        String name = path.toString();
        for (Path.Node node : path) {
            if (node.getName() != null) {
                name = node.getName();
            }
        }
        return name;
    }

    private static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
        String slug = code.toLowerCase(Locale.ROOT).replace('_', '-');
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:bank-core:error:" + slug));
        problem.setTitle(titleOf(slug));
        problem.setProperty("code", code);
        return problem;
    }

    /** "account-not-found" becomes "Account not found", and an acronym keeps its capitals: "Invalid IBAN". */
    private static String titleOf(String slug) {
        String title = Arrays.stream(slug.split("-"))
                .map(word -> ACRONYMS.contains(word) ? word.toUpperCase(Locale.ROOT) : word)
                .collect(Collectors.joining(" "));
        return Character.toUpperCase(title.charAt(0)) + title.substring(1);
    }
}
