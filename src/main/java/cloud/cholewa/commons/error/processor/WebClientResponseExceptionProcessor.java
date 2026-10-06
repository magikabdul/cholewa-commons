package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.DownstreamErrors;
import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

@Slf4j
public class WebClientResponseExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        final WebClientResponseException webClientResponseException = (WebClientResponseException) throwable;

        final HttpStatus httpStatus =
            Optional.ofNullable(HttpStatus.resolve(webClientResponseException.getStatusCode().value()))
                .orElse(HttpStatus.INTERNAL_SERVER_ERROR);

        if (httpStatus.is5xxServerError()) {
            log.error(
                "Handled [{}]: downstream response {}, {}",
                throwable.getClass().getSimpleName(),
                webClientResponseException.getStatusCode(),
                throwable.getLocalizedMessage()
            );
        } else {
            log.warn(
                "Handled [{}]: downstream response {}, {}",
                throwable.getClass().getSimpleName(),
                webClientResponseException.getStatusCode(),
                throwable.getLocalizedMessage()
            );
        }

        //first what this service knows - which call failed and how - exactly as before 1.7.0; then
        //the causes the downstream service named. Without the second part a code dies at the
        //first hop: the caller of this service sees a 404 and cannot tell a missing record from
        //a missing route.
        //Only messages that carry a code travel, and without their details. A code is something
        //a service chose to publish; details are by convention the raw exception text - SQL, a
        //driver message, an internal host - and a message without a code may come from anything
        //that happens to answer with an "errors" array. Neither may reach a caller two hops away
        final Set<ErrorMessage> errors = new LinkedHashSet<>();
        errors.add(ErrorMessage.builder().message(throwable.getLocalizedMessage()).build());
        DownstreamErrors.messagesOf(webClientResponseException.getResponseBodyAsString(StandardCharsets.UTF_8))
            .stream()
            .filter(downstream -> downstream.getCode() != null && !downstream.getCode().isBlank())
            .map(downstream -> ErrorMessage.builder()
                .message(downstream.getMessage())
                .code(downstream.getCode())
                .build())
            .forEach(errors::add);

        return Errors.builder()
            .httpStatus(httpStatus)
            .errors(errors)
            .build();
    }
}
