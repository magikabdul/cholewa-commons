package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.Optional;

@Slf4j
public class ResponseStatusExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        log.error("Handled [{}]: {}", throwable.getClass().getSimpleName(), throwable.getMessage());

        final ResponseStatusException exception = (ResponseStatusException) throwable;

        final HttpStatus httpStatus = Optional.ofNullable(HttpStatus.resolve(exception.getStatusCode().value()))
            .orElse(HttpStatus.INTERNAL_SERVER_ERROR);

        return Errors.builder()
            .httpStatus(httpStatus)
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message(Optional.ofNullable(exception.getReason()).orElse(httpStatus.getReasonPhrase()))
                    .build()
            ))
            .build();
    }
}
