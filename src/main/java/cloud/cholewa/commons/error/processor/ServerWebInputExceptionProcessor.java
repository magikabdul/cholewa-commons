package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebInputException;

import java.util.Collections;
import java.util.Optional;

public class ServerWebInputExceptionProcessor implements ExceptionProcessor {

    private static final int MAX_CAUSE_DEPTH = 16;

    @Override
    public Errors apply(final Throwable throwable) {
        final ServerWebInputException exception = (ServerWebInputException) throwable;

        return Errors.builder()
            .httpStatus(HttpStatus.BAD_REQUEST)
            .errors(Collections.singleton(buildErrorMessage(exception)))
            .build();
    }

    private ErrorMessage buildErrorMessage(final ServerWebInputException exception) {
        return findDecodingCause(exception)
            .map(this::decodingErrorMessage)
            .orElseGet(() -> inputErrorMessage(exception));
    }

    private ErrorMessage decodingErrorMessage(final DecodingException decodingException) {
        if (decodingException.getCause() == null) {
            return ErrorMessage.builder()
                .message("Missing request body")
                .build();
        }

        return ErrorMessage.builder()
            .message("Malformed request body")
            .details(decodingException.getMostSpecificCause().getMessage())
            .build();
    }

    private ErrorMessage inputErrorMessage(final ServerWebInputException exception) {
        return ErrorMessage.builder()
            .message(Optional.ofNullable(exception.getReason()).orElse("Invalid request input"))
            .details(exception.getCause() == null ? null : exception.getMostSpecificCause().getMessage())
            .build();
    }

    private Optional<DecodingException> findDecodingCause(final Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;

        while (current != null && !(current instanceof DecodingException) && depth++ < MAX_CAUSE_DEPTH) {
            current = current.getCause();
        }

        return current instanceof DecodingException decodingException
            ? Optional.of(decodingException)
            : Optional.empty();
    }
}
