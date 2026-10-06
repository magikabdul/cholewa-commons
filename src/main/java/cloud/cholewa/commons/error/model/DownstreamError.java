package cloud.cholewa.commons.error.model;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * An error answered by another service, as read by {@code DownstreamErrors}.<br>
 * {@code status} - the status of the response itself; {@link Errors#getHttpStatus()} never survives
 * the wire.<br>
 * {@code errors} - the messages of the body, in the order they were read, empty when the body was
 * missing or was not the {@link Errors} contract. Never null, never containing null, and
 * unmodifiable: copy it before adding to it.
 */
public record DownstreamError(HttpStatusCode status, Set<ErrorMessage> errors) {

    public DownstreamError {
        final Set<ErrorMessage> copy = new LinkedHashSet<>();
        if (errors != null) {
            //a body may well carry "errors":[null]
            errors.stream().filter(Objects::nonNull).forEach(copy::add);
        }
        errors = Collections.unmodifiableSet(copy);
    }

    /**
     * The status as an {@link HttpStatus}, for the many signatures that take one. A status Spring
     * has no constant for - a proxy's 520, say - gives the fallback instead of an exception.
     */
    public HttpStatus httpStatus(final HttpStatus fallback) {
        final HttpStatus resolved = HttpStatus.resolve(status.value());

        return resolved == null ? fallback : resolved;
    }

    /**
     * Whether the downstream service named this cause. A status alone cannot tell "the thing you
     * asked for does not exist" from "this path does not exist"; a code can.
     */
    public boolean hasCode(final String code) {
        return code != null && errors.stream().anyMatch(error -> code.equals(error.getCode()));
    }

    public boolean hasCode(final ErrorId errorId) {
        return hasCode(ErrorId.codeOf(errorId));
    }
}
