package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.model.ErrorId;
import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.util.Collections;
import java.util.Objects;

/**
 * The processor of a domain exception of a service (HAS-213, moved from database-service): domain exceptions
 * differ only in the status they answer and the cause they name, so both are parameters - register one instance
 * per exception with {@code withCustomErrorProcessor}.
 * <p>
 * The cause travels twice: its description as the {@code message}, for people, and its name as the {@code code}
 * ({@link ErrorId#codeOf}), for a caller that has to tell one error from another without parsing text. A 4xx
 * carries the exception message as {@code details} - it is written for the caller - and is logged at WARN; a
 * 5xx keeps it out of the response (whatever failed inside stays in the log) and is logged at ERROR with the
 * stack trace. The code is the name of an enum constant - an {@code ErrorId} that is not an enum answers without
 * one. Only an error status (4xx or 5xx) is accepted.
 */
@Slf4j
public class DomainExceptionProcessor implements ExceptionProcessor {

    private final HttpStatus status;
    private final ErrorId cause;

    //refused where it is registered, at the start - not as a failure of the first request it should answer
    public DomainExceptionProcessor(final HttpStatus status, final ErrorId cause) {
        this.status = Objects.requireNonNull(status, "status");
        this.cause = Objects.requireNonNull(cause, "cause");
        if (!status.isError()) {
            throw new IllegalArgumentException("Not an error status: " + status);
        }
    }

    @Override
    public Errors apply(final Throwable throwable) {
        final boolean serverError = status.is5xxServerError();

        if (serverError) {
            //a failure of the service: the stack trace is what there is to diagnose it from
            log.error("Handled [{}]: {}", throwable.getClass().getSimpleName(), throwable.getMessage(), throwable);
        } else {
            //a 4xx is the caller's mistake: at ERROR it would feed an error-rate alert for nothing
            log.warn("Handled [{}]: {}", throwable.getClass().getSimpleName(), throwable.getMessage());
        }

        return Errors.builder()
            .httpStatus(status)
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message(cause.getDescription())
                    .details(serverError ? null : throwable.getMessage())
                    .code(ErrorId.codeOf(cause))
                    .build()
            ))
            .build();
    }
}
