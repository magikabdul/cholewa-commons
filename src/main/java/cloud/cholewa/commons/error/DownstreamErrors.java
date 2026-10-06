package cloud.cholewa.commons.error;

import cloud.cholewa.commons.error.model.DownstreamError;
import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Reads an error response of another service that speaks the {@link Errors} contract.
 * <pre>{@code
 * webClient.get().uri(...)
 *     .retrieve()
 *     .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
 *         .map(error -> new MyCallException(error.status(), error.errors())))
 *     .bodyToMono(MyReply.class);
 * }</pre>
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DownstreamErrors {

    /**
     * The status comes from the response, never from the body: {@code Errors.httpStatus} is
     * {@code @JsonIgnore}, so it is always null after decoding. Whatever happens to the body - none
     * at all, a proxy's HTML page, JSON of another shape, a connection lost half way - the result
     * still carries the status, with no messages; the body must never hide it.
     */
    public static Mono<DownstreamError> read(final ClientResponse response) {
        final HttpStatusCode status = response.statusCode();

        return response.bodyToMono(Errors.class)
            .map(errors -> messagesOf(errors, status))
            //only the type of the failure is logged: a decoding error may quote the body
            .onErrorResume(throwable -> {
                log.warn("Unreadable error body of a downstream response ({}): {}",
                    status.value(), throwable.getClass().getSimpleName());
                return Mono.just(Set.of());
            })
            //no body at all is nothing to warn about
            .defaultIfEmpty(Set.of())
            .map(messages -> new DownstreamError(status, messages));
    }

    private static Set<ErrorMessage> messagesOf(final Errors errors, final HttpStatusCode status) {
        if (errors.getErrors() == null || errors.getErrors().isEmpty()) {
            //valid JSON, but not this contract - Spring's default error body, for one
            log.warn("Error body of a downstream response ({}) is not the Errors contract", status.value());
            return Set.of();
        }
        return errors.getErrors();
    }
}
