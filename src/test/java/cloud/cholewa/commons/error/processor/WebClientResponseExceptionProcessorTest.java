package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class WebClientResponseExceptionProcessorTest {

    private final WebClientResponseExceptionProcessor sut = new WebClientResponseExceptionProcessor();

    //without this the code dies at the first hop: the caller of this service sees a 404 and cannot
    //tell a missing record from a missing route
    @Test
    void should_relay_the_messages_and_codes_of_a_downstream_errors_body_after_its_own_message() {
        final WebClientResponseException downstream = downstream(404, "Not Found", """
            {"errors":[{"message":"Device configuration not found","details":"point 48","code":"NOT_FOUND_DEVICE_CONFIGURATION"},{"message":"second"}]}
            """);

        final Errors errors = sut.apply(downstream);

        assertThat(errors.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errors.getErrors()).containsExactly(
            //the entry of the releases before 1.7.0, still first and unchanged
            ErrorMessage.builder().message(downstream.getLocalizedMessage()).build(),
            ErrorMessage.builder()
                .message("Device configuration not found")
                .details("point 48")
                .code("NOT_FOUND_DEVICE_CONFIGURATION")
                .build(),
            ErrorMessage.builder().message("second").build()
        );
    }

    @Test
    void should_answer_as_before_when_the_body_is_not_the_errors_contract() {
        for (final String body : new String[]{
            "",
            "<html><body>502 Bad Gateway</body></html>",
            "{\"timestamp\":\"2026-10-06T08:00:00Z\",\"status\":500,\"error\":\"Internal Server Error\"}",
            "{\"errors\":[{\"message\"",
            "{\"errors\":\"boom\"}"
        }) {
            final WebClientResponseException downstream = downstream(500, "Internal Server Error", body);

            final Errors errors = sut.apply(downstream);

            assertThat(errors.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(errors.getErrors())
                .as("body: %s", body)
                .containsExactly(ErrorMessage.builder().message(downstream.getLocalizedMessage()).build());
        }
    }

    @Test
    void should_answer_as_before_when_there_is_no_body_at_all() {
        final WebClientResponseException downstream =
            WebClientResponseException.create(503, "Service Unavailable", HttpHeaders.EMPTY, null, null);

        assertThat(sut.apply(downstream).getErrors())
            .containsExactly(ErrorMessage.builder().message(downstream.getLocalizedMessage()).build());
    }

    //a status Spring has no constant for is answered as 500, as it always was
    @Test
    void should_answer_500_for_a_status_without_a_constant() {
        assertThat(sut.apply(downstream(599, "Network Connect Timeout", "")).getHttpStatus())
            .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static WebClientResponseException downstream(final int status, final String reason, final String body) {
        return WebClientResponseException.create(
            status, reason, HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }
}
