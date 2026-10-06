package cloud.cholewa.commons.error.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * {@code code} - a stable, machine-readable name of the cause, for a caller that has to tell errors
 * apart without parsing {@code message}; usually the name of an {@link ErrorId} constant
 * ({@link ErrorId#codeOf(ErrorId)}). Optional: absent from the JSON unless set.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class ErrorMessage {

    private String message;
    private String details;
    private String code;

    //the all-args constructor of the releases before 1.7.0, kept for the callers compiled against it
    public ErrorMessage(final String message, final String details) {
        this(message, details, null);
    }
}
