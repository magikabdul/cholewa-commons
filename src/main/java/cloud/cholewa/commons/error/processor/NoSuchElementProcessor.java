package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.util.Collections;

@Slf4j
public class NoSuchElementProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {

        return Errors.builder()
            .httpStatus(HttpStatus.BAD_REQUEST)
            .errors(Collections.singleton(
                ErrorMessage.builder()
                    .message(throwable.getMessage())
                    .details(throwable.getLocalizedMessage())
                    .build()
            ))
            .build();
    }
}
