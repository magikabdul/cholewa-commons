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
    void should_relay_the_named_causes_of_a_downstream_errors_body_after_its_own_message() {
        final WebClientResponseException downstream = downstream(404, "Not Found", """
            {"errors":[{"message":"Device configuration not found","details":"point 48","code":"NOT_FOUND_DEVICE_CONFIGURATION"},{"message":"Another cause","code":"OTHER"}]}
            """);

        final Errors errors = sut.apply(downstream);

        assertThat(errors.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errors.getErrors()).containsExactly(
            //the entry of the releases before 1.7.0, still first and unchanged
            ErrorMessage.builder().message(downstream.getLocalizedMessage()).build(),
            //message and code travel; the details stay behind
            ErrorMessage.builder()
                .message("Device configuration not found")
                .code("NOT_FOUND_DEVICE_CONFIGURATION")
                .build(),
            ErrorMessage.builder().message("Another cause").code("OTHER").build()
        );
    }

    //what DefaultExceptionProcessor of a downstream service answers: the raw exception text in
    //the details and no code. None of it may reach a caller two hops away
    @Test
    void should_not_relay_a_downstream_message_without_a_code() {
        final WebClientResponseException downstream = downstream(500, "Internal Server Error", """
            {"errors":[{"message":"Unhandled error, update processor configuration","details":"ERROR: relation \"eaton_device\" does not exist; host db-internal:25060"}]}
            """);

        final Errors errors = sut.apply(downstream);

        assertThat(errors.getErrors())
            .containsExactly(ErrorMessage.builder().message(downstream.getLocalizedMessage()).build());
        assertThat(errors.toString()).doesNotContain("eaton_device", "db-internal");
    }

    @Test
    void should_never_relay_details_even_with_a_code() {
        final WebClientResponseException downstream = downstream(500, "Internal Server Error", """
            {"errors":[{"message":"Storage failed","details":"password authentication failed for user admin","code":"STORAGE_FAILED"}]}
            """);

        final Errors errors = sut.apply(downstream);

        assertThat(errors.getErrors()).extracting(ErrorMessage::getCode).containsExactly(null, "STORAGE_FAILED");
        assertThat(errors.getErrors()).extracting(ErrorMessage::getDetails).containsOnlyNulls();
    }

    //Spring's own body for a failed binding: an "errors" array whose objects have a code and no
    //message. It is not this contract, and its codes are not ours to pass on
    @Test
    void should_not_relay_the_binding_errors_of_a_spring_error_body() {
        final WebClientResponseException downstream = downstream(400, "Bad Request", """
            {"timestamp":"2026-10-06T08:00:00Z","status":400,"errors":[{"field":"name","defaultMessage":"must not be blank","code":"NotBlank"}]}
            """);

        assertThat(sut.apply(downstream).getErrors())
            .containsExactly(ErrorMessage.builder().message(downstream.getLocalizedMessage()).build());
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
