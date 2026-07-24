package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;

import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
public class WebExchangeBindExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        WebExchangeBindException exception = (WebExchangeBindException) throwable;

        log.warn(
            "Handled [{}]: {} validation error(s), fields: {}",
            throwable.getClass().getSimpleName(),
            exception.getErrorCount(),
            exception.getFieldErrors().stream().map(FieldError::getField).collect(Collectors.toSet())
        );

        return Errors.builder()
            .httpStatus(HttpStatus.BAD_REQUEST)
            .errors(exception.getAllErrors().stream()
                .map(objectError -> ErrorMessage.builder()
                    .message("Missing or invalid parameter")
                    .details(Optional.ofNullable(objectError.getArguments())
                        .map(args -> (DefaultMessageSourceResolvable) args[0] )
                        .map(DefaultMessageSourceResolvable::getDefaultMessage)
                        .orElseGet(() -> {
                            log.info("No relevant information to extract");
                            return null;
                        }))
                    .build()
                )
                .collect(Collectors.toSet()))
            .build();
    }
}
