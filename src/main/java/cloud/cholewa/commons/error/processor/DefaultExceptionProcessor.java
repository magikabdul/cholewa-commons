package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.util.Collections;

@Slf4j
public class DefaultExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {

        log.error(
            "Generic exception [{}]: {}",
            throwable.getClass().getName(),
            throwable.getLocalizedMessage()
        );

        return Errors.builder()
            .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message("Unhandled error, update processor configuration")
                    .details(throwable.getLocalizedMessage())
                    .build()
            ))
            .build();
    }
}
