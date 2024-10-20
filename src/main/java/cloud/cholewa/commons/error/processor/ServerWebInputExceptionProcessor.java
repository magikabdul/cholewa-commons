package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import org.springframework.core.codec.DecodingException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebInputException;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;

public class ServerWebInputExceptionProcessor implements ExceptionProcessor {

    @Override
    public Errors apply(final Throwable throwable) {
        final Throwable cause = ((ServerWebInputException) throwable).getMostSpecificCause();

        ErrorMessage errorMessage = ErrorMessage.builder()
            .message("Unknown type of ServerWebInputException")
            .build();

        if (cause instanceof ServerWebInputException serverWebInputException) {
            return Errors.builder()
                .httpStatus(HttpStatus.BAD_REQUEST)
                .errors(Collections.singleton(
                    handleServerWebInputException(serverWebInputException, errorMessage)
                ))
                .build();
        } else if (cause instanceof DecodingException) {
            return Errors.builder()
                .httpStatus(HttpStatus.BAD_REQUEST)
                .errors(handleDecodingException(cause, errorMessage))
                .build();
        } else {
            return Errors.builder()
                .httpStatus(HttpStatus.BAD_REQUEST)
                .errors(handleUnknownTypeOfServerWebInputException(cause, errorMessage))
                .build();
        }
    }

    private ErrorMessage handleServerWebInputException(
        final ServerWebInputException ex,
        final ErrorMessage errorMessage
    ) {
        return Optional.ofNullable(ex.getMethodParameter())
            .map(methodParameter -> {
                errorMessage.setMessage("aaaa");
                errorMessage.setDetails("det");
                return errorMessage;
            })
            .orElse(errorMessage);
    }

    private Set<ErrorMessage> handleUnknownTypeOfServerWebInputException(
        final Throwable throwable,
        final ErrorMessage errorMessage
    ) {
        errorMessage.setDetails(throwable.fillInStackTrace().toString());

        return Collections.singleton(errorMessage);
    }

    private Set<ErrorMessage> handleDecodingException(
        final Throwable throwable,
        final ErrorMessage errorMessage
    ) {
        errorMessage.setMessage("Missing request body");
        errorMessage.setDetails(throwable.fillInStackTrace().toString());

        return Collections.singleton(errorMessage);
    }
}
