package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import org.springframework.http.HttpStatus;

import java.util.Collections;

public class DuplicateKeyExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        return Errors.builder()
            .httpStatus(HttpStatus.BAD_REQUEST)
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message("Duplicate Key")
                    .details(throwable.getMessage())
                    .build()
            ))
            .build();
    }
}
