package cloud.cholewa.commons.error.model;

import org.springframework.http.HttpStatusCode;

import java.util.Set;

/**
 * An error answered by another service, as read by {@code DownstreamErrors}.<br>
 * {@code status} - the status of the response itself; {@link Errors#getHttpStatus()} never survives
 * the wire.<br>
 * {@code errors} - the messages of the body, empty when the body was missing or was not the
 * {@link Errors} contract. Never null.
 */
public record DownstreamError(HttpStatusCode status, Set<ErrorMessage> errors) {

    public DownstreamError {
        errors = errors == null ? Set.of() : Set.copyOf(errors);
    }

    /**
     * Whether the downstream service named this cause. A status alone cannot tell "the thing you
     * asked for does not exist" from "this path does not exist"; a code can.
     */
    public boolean hasCode(final String code) {
        return code != null && errors.stream().anyMatch(error -> code.equals(error.getCode()));
    }

    public boolean hasCode(final ErrorId errorId) {
        return errorId != null && hasCode(errorId.getCode());
    }
}
