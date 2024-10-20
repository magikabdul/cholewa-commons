package cloud.cholewa.commons.error.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.util.HashSet;
import java.util.Set;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public class Errors {

    private Set<ErrorMessage> errors;

    @JsonIgnore
    private HttpStatus httpStatus;

    public Errors addError(final ErrorMessage errorMessage) {
        if (this.errors == null) {
            this.errors = new HashSet<>();
        }
        this.errors.add(errorMessage);
        return this;
    }
}
