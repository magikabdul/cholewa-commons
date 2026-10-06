package cloud.cholewa.commons.error;

import cloud.cholewa.commons.error.model.DownstreamError;
import cloud.cholewa.commons.error.model.ErrorId;
import cloud.cholewa.commons.error.model.ErrorMessage;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DownstreamErrorsTest {

    @Test
    void should_read_status_and_messages_of_a_client_error() {
        final ClientResponse response = json(HttpStatus.NOT_FOUND, """
            {"errors":[{"message":"Device configuration not found","details":"point 48","code":"NOT_FOUND_DEVICE_CONFIGURATION"}]}
            """);

        DownstreamErrors.read(response)
            .as(StepVerifier::create)
            .assertNext(error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(error.errors()).containsExactly(ErrorMessage.builder()
                    .message("Device configuration not found")
                    .details("point 48")
                    .code("NOT_FOUND_DEVICE_CONFIGURATION")
                    .build());
                assertThat(error.hasCode("NOT_FOUND_DEVICE_CONFIGURATION")).isTrue();
            })
            .verifyComplete();
    }

    @Test
    void should_read_status_and_messages_of_a_server_error() {
        final ClientResponse response = json(HttpStatus.SERVICE_UNAVAILABLE, """
            {"errors":[{"message":"first"},{"message":"second","details":"why"}]}
            """);

        DownstreamErrors.read(response)
            .as(StepVerifier::create)
            .assertNext(error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.errors()).extracting(ErrorMessage::getMessage)
                    .containsExactlyInAnyOrder("first", "second");
                assertThat(error.errors()).extracting(ErrorMessage::getCode).containsOnlyNulls();
            })
            .verifyComplete();
    }

    //a service on an older release of this library: no code in the body, and the 404 is still a 404
    @Test
    void should_read_a_body_without_codes() {
        final ClientResponse response = json(HttpStatus.NOT_FOUND, """
            {"errors":[{"message":"Device configuration not found"}]}
            """);

        DownstreamErrors.read(response)
            .as(StepVerifier::create)
            .assertNext(error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(error.hasCode("NOT_FOUND_DEVICE_CONFIGURATION")).isFalse();
            })
            .verifyComplete();
    }

    //a newer sender may add fields this release does not know
    @Test
    void should_ignore_fields_it_does_not_know() {
        final ClientResponse response = json(HttpStatus.BAD_REQUEST, """
            {"errors":[{"message":"m","code":"C","severity":"high"}],"traceId":"abc"}
            """);

        DownstreamErrors.read(response)
            .as(StepVerifier::create)
            .assertNext(error -> assertThat(error.hasCode("C")).isTrue())
            .verifyComplete();
    }

    //Spring's default error body, for one: a routing 404 of a service that is up
    @Test
    void should_keep_the_status_when_json_body_is_not_the_errors_contract() {
        final ClientResponse response = json(HttpStatus.NOT_FOUND, """
            {"timestamp":"2026-10-06T08:00:00Z","path":"/home/device","status":404,"error":"Not Found"}
            """);

        expectStatusWithoutMessages(response, HttpStatus.NOT_FOUND);
    }

    @Test
    void should_keep_the_status_when_body_is_a_proxy_page() {
        final ClientResponse response = ClientResponse.create(HttpStatus.BAD_GATEWAY)
            .header("Content-Type", MediaType.TEXT_HTML_VALUE)
            .body("<html><body><h1>502 Bad Gateway</h1></body></html>")
            .build();

        expectStatusWithoutMessages(response, HttpStatus.BAD_GATEWAY);
    }

    @Test
    void should_keep_the_status_when_json_is_malformed() {
        expectStatusWithoutMessages(json(HttpStatus.INTERNAL_SERVER_ERROR, "{\"errors\":[{\"message\""),
            HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void should_keep_the_status_when_there_is_no_body() {
        expectStatusWithoutMessages(ClientResponse.create(HttpStatus.GATEWAY_TIMEOUT).build(),
            HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void should_keep_the_status_when_errors_is_empty() {
        expectStatusWithoutMessages(json(HttpStatus.CONFLICT, "{\"errors\":[]}"), HttpStatus.CONFLICT);
    }

    //the connection is lost while the body is still arriving
    @Test
    void should_keep_the_status_when_the_body_breaks_off() {
        final DataBuffer start = DefaultDataBufferFactory.sharedInstance
            .wrap("{\"errors\":[".getBytes(StandardCharsets.UTF_8));
        final ClientResponse response = ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(Flux.just(start).concatWith(Flux.error(new IOException("Connection reset"))))
            .build();

        expectStatusWithoutMessages(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    //a status Spring has no constant for is still a status
    @Test
    void should_keep_a_status_outside_the_known_ones() {
        DownstreamErrors.read(ClientResponse.create(HttpStatusCode.valueOf(599)).build())
            .as(StepVerifier::create)
            .assertNext(error -> assertThat(error.status().value()).isEqualTo(599))
            .verifyComplete();
    }

    @Test
    void should_match_a_code_by_error_id() {
        final DownstreamError error = new DownstreamError(HttpStatus.NOT_FOUND, Set.of(
            ErrorMessage.builder().message("a").build(),
            ErrorMessage.builder().message("b").code("KNOWN").build()
        ));

        assertThat(error.hasCode(SampleError.KNOWN)).isTrue();
        assertThat(error.hasCode(SampleError.OTHER)).isFalse();
        assertThat(error.hasCode((String) null)).isFalse();
        assertThat(error.hasCode((ErrorId) null)).isFalse();
    }

    @Test
    void should_never_carry_null_messages() {
        assertThat(new DownstreamError(HttpStatus.BAD_GATEWAY, null).errors()).isEmpty();
    }

    private static void expectStatusWithoutMessages(final ClientResponse response, final HttpStatus status) {
        DownstreamErrors.read(response)
            .as(StepVerifier::create)
            .assertNext(error -> {
                assertThat(error.status()).isEqualTo(status);
                assertThat(error.errors()).isEmpty();
            })
            .verifyComplete();
    }

    private static ClientResponse json(final HttpStatus status, final String body) {
        return ClientResponse.create(status)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(body)
            .build();
    }

    private enum SampleError implements ErrorId {
        KNOWN, OTHER;

        @Override
        public String getDescription() {
            return "a description worded for people";
        }
    }
}
