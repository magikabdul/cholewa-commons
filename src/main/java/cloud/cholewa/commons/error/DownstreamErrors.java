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
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Set;

/**
 * Reads an error response of another service that speaks the {@link Errors} contract.
 * <pre>{@code
 * webClient.get().uri(...)
 *     .retrieve()
 *     .onStatus(HttpStatusCode::isError, response -> DownstreamErrors.read(response)
 *         .map(error -> new MyCallException(error.httpStatus(HttpStatus.BAD_GATEWAY), error.errors())))
 *     .bodyToMono(MyReply.class);
 * }</pre>
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DownstreamErrors {

    /** How long {@link #read(ClientResponse)} waits for the body before answering with the status alone. */
    public static final Duration DEFAULT_BODY_TIMEOUT = Duration.ofSeconds(5);

    private static final String ERRORS_FIELD = "errors";

    //its own mapper, not the application's codecs: the body is read as text and parsed here, so
    //that a JSON body under another Content-Type (a proxy that rewrote it) is still understood
    private static final JsonMapper MAPPER = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    public static Mono<DownstreamError> read(final ClientResponse response) {
        return read(response, DEFAULT_BODY_TIMEOUT);
    }

    /**
     * The status comes from the response, never from the body: {@code Errors.httpStatus} is
     * {@code @JsonIgnore}, so it is always null after decoding. Whatever happens to the body - none
     * at all, a proxy's HTML page, JSON of another shape, a connection lost half way, a body that
     * never ends - the result still carries the status, with no messages; the body must never hide
     * it. Nothing here can make the returned Mono fail.
     *
     * @param bodyTimeout the longest wait for the body; the headers have arrived, and a body that
     *                    stalls must not keep the status from the caller
     */
    public static Mono<DownstreamError> read(final ClientResponse response, final Duration bodyTimeout) {
        final HttpStatusCode status = response.statusCode();

        return response.bodyToMono(String.class)
            .timeout(bodyTimeout)
            .map(body -> messagesOf(body, status))
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

    private static Set<ErrorMessage> messagesOf(final String body, final HttpStatusCode status) {
        if (body.isBlank()) {
            return Set.of();
        }
        final JsonNode root = MAPPER.readTree(body);

        //valid JSON of another shape - Spring's default error body, for one. An Errors body with
        //no messages is the contract all the same, and nothing to warn about
        if (!root.isObject() || !root.has(ERRORS_FIELD)) {
            log.warn("Error body of a downstream response ({}) is not the Errors contract", status.value());
            return Set.of();
        }
        final Set<ErrorMessage> messages = MAPPER.treeToValue(root, Errors.class).getErrors();

        return messages == null ? Set.of() : messages;
    }
}
