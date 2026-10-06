package cloud.cholewa.commons.error.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorMessageTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void should_round_trip_with_a_code() {
        final ErrorMessage message = ErrorMessage.builder()
            .message("Device configuration not found")
            .details("point 48")
            .code("NOT_FOUND_DEVICE_CONFIGURATION")
            .build();

        final String json = mapper.writeValueAsString(message);

        assertThat(json).contains("\"code\":\"NOT_FOUND_DEVICE_CONFIGURATION\"");
        assertThat(mapper.readValue(json, ErrorMessage.class)).isEqualTo(message);
    }

    //the body of every error answered before 1.7.0 must not change: no "code" key at all
    @Test
    void should_round_trip_without_a_code_and_leave_it_out_of_the_json() {
        final ErrorMessage message = ErrorMessage.builder().message("Duplicate Key").build();

        final String json = mapper.writeValueAsString(message);

        assertThat(json).isEqualTo("{\"message\":\"Duplicate Key\"}");
        assertThat(mapper.readValue(json, ErrorMessage.class)).isEqualTo(message);
    }

    @Test
    void should_leave_an_empty_code_out_of_the_json() {
        assertThat(mapper.writeValueAsString(ErrorMessage.builder().message("m").code("").build()))
            .isEqualTo("{\"message\":\"m\"}");
    }

    //callers compiled against the two-argument constructor of the earlier releases
    @Test
    void should_keep_the_two_argument_constructor() {
        final ErrorMessage message = new ErrorMessage("m", "d");

        assertThat(message.getMessage()).isEqualTo("m");
        assertThat(message.getDetails()).isEqualTo("d");
        assertThat(message.getCode()).isNull();
    }

    @Test
    void should_take_the_code_from_the_enum_constant_name() {
        assertThat(UniqueError.NOT_IMPLEMENTED.getCode()).isEqualTo("NOT_IMPLEMENTED");
    }

    //an ErrorId that is not an enum has no name to offer
    @Test
    void should_have_no_code_for_an_error_id_that_is_not_an_enum() {
        final ErrorId notAnEnum = () -> "described only";

        assertThat(notAnEnum.getCode()).isNull();
    }
}
