package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.util.stream.Collectors;

@Slf4j
public class ConstraintViolationExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        log.warn("Handled [{}]: {}", throwable.getClass().getSimpleName(), throwable.getMessage());

        return Errors.builder()
            .httpStatus(HttpStatus.BAD_REQUEST)
            .errors(
                ((ConstraintViolationException) throwable).getConstraintViolations().stream()
                    .map(constraintViolation -> getErrorMessage(constraintViolation, throwable))
                    .collect(Collectors.toSet())
            )
            .build();
    }

    private ErrorMessage getErrorMessage(final ConstraintViolation<?> violation, final Throwable throwable) {
        return ErrorMessage.builder()
            .message(violation.getMessage())
            .details(throwable.getLocalizedMessage())
            .build();
    }
}
