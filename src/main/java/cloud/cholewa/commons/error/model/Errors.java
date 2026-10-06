package cloud.cholewa.commons.error.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.util.LinkedHashSet;
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
        //a copy every time: the set this object was built with is often one that cannot be added
        //to - Collections.singleton in the processors, the messages of a DownstreamError
        final Set<ErrorMessage> extended = this.errors == null ? new LinkedHashSet<>() : new LinkedHashSet<>(this.errors);
        extended.add(errorMessage);
        this.errors = extended;
        return this;
    }
}
