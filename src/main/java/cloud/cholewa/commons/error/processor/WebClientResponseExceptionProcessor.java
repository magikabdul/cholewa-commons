package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.Collections;

@Slf4j
public class WebClientResponseExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        final WebClientResponseException webClientResponseException = (WebClientResponseException) throwable;

        log.error(
            "Webclient got response code: {}, error: {}",
            webClientResponseException.getStatusCode(),
            throwable.getLocalizedMessage()
        );

        return Errors.builder()
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
