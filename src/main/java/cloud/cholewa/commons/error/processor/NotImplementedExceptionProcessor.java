package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import org.springframework.http.HttpStatus;

import java.util.Collections;

import static cloud.cholewa.commons.error.model.UniqueError.NOT_IMPLEMENTED;

public class NotImplementedExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        return Errors.builder()
            .httpStatus(HttpStatus.NOT_IMPLEMENTED)
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message("Not implemented yet")
                    .details(NOT_IMPLEMENTED.getDescription())
                    .build()
            ))
            .build();
    }
}
