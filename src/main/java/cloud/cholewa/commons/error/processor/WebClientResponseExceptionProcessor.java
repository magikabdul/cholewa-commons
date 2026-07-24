package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Collections;
import java.util.Optional;

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

        return Errors.builder()
            .httpStatus(httpStatus)
            .errors(
                Collections.singleton(
                    ErrorMessage.builder()
                        .message(throwable.getLocalizedMessage())
                        .build()
                )
            )
            .build();
    }
}
