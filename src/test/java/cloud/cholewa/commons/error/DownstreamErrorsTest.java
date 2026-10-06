package cloud.cholewa.commons.error;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.commons.error.model.DownstreamError;
import cloud.cholewa.commons.error.model.ErrorId;
import cloud.cholewa.commons.error.model.ErrorMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DownstreamErrorsTest {

    private static final String WITH_CODE = """
        {"errors":[{"message":"Device configuration not found","details":"point 48","code":"NOT_FOUND_DEVICE_CONFIGURATION"}]}
        """;

    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureTheLog() {
        logged.start();
        ((Logger) LoggerFactory.getLogger(DownstreamErrors.class)).addAppender(logged);
    }

    @AfterEach
    void releaseTheLog() {
        ((Logger) LoggerFactory.getLogger(DownstreamErrors.class)).detachAppender(logged);
    }

    @Test
    void should_read_status_and_messages_of_a_client_error() {
        DownstreamErrors.read(json(HttpStatus.NOT_FOUND, WITH_CODE))
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

        assertThat(logged.list).isEmpty();
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

    //bound as a whole, a Set comes back from Jackson as a HashSet; with this many messages hash
    //order and body order differ, so the test fails the moment the order is lost again
    @Test
    void should_keep_the_order_of_the_body() {
        final ClientResponse response = json(HttpStatus.BAD_REQUEST, """
            {"errors":[{"message":"c"},{"message":"a"},{"message":"zz"},{"message":"b"},{"message":"e"},{"message":"d"}]}
            """);

        DownstreamErrors.read(response)
            .as(StepVerifier::create)
            .assertNext(error -> assertThat(error.errors()).extracting(ErrorMessage::getMessage)
                .containsExactly("c", "a", "zz", "b", "e", "d"))
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

    //a proxy on the way rewrote or dropped the Content-Type; the code in the body is the very
    //thing that tells "no such record" from a routing 404, so it must not be lost with the header
    @Test
    void should_read_the_errors_contract_under_another_content_type() {
        final ClientResponse plainText = ClientResponse.create(HttpStatus.NOT_FOUND)
            .header("Content-Type", MediaType.TEXT_PLAIN_VALUE)
            .body(WITH_CODE)
            .build();
        final ClientResponse noContentType = ClientResponse.create(HttpStatus.NOT_FOUND).body(WITH_CODE).build();

        for (final ClientResponse response : new ClientResponse[]{plainText, noContentType}) {
            DownstreamErrors.read(response)
                .as(StepVerifier::create)
                .assertNext(error -> assertThat(error.hasCode("NOT_FOUND_DEVICE_CONFIGURATION")).isTrue())
                .verifyComplete();
        }
    }

    //Spring's default error body, for one: a routing 404 of a service that is up
    @Test
    void should_keep_the_status_and_warn_when_json_body_is_not_the_errors_contract() {
        final ClientResponse response = json(HttpStatus.NOT_FOUND, """
            {"timestamp":"2026-10-06T08:00:00Z","path":"/home/device","status":404,"error":"Not Found"}
            """);

        expectStatusWithoutMessages(response, HttpStatus.NOT_FOUND);
        assertThat(warnings()).containsExactly("Error body of a downstream response (404) is not the Errors contract");
    }

    @Test
    void should_keep_the_status_when_json_is_not_an_object() {
        expectStatusWithoutMessages(json(HttpStatus.BAD_GATEWAY, "[1,2,3]"), HttpStatus.BAD_GATEWAY);
        assertThat(warnings()).hasSize(1);
    }

    @Test
    void should_keep_the_status_when_body_is_a_proxy_page() {
        final ClientResponse response = ClientResponse.create(HttpStatus.BAD_GATEWAY)
            .header("Content-Type", MediaType.TEXT_HTML_VALUE)
            .body("<html><body><h1>502 Bad Gateway</h1></body></html>")
            .build();

        expectStatusWithoutMessages(response, HttpStatus.BAD_GATEWAY);
        //the kind of failure only: the body itself, which a parser may quote, stays out of the log
        assertThat(warnings()).hasSize(1).allSatisfy(warning -> assertThat(warning).doesNotContain("Bad Gateway"));
    }

    @Test
    void should_keep_the_status_when_json_is_malformed() {
        expectStatusWithoutMessages(json(HttpStatus.INTERNAL_SERVER_ERROR, "{\"errors\":[{\"message\""),
            HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void should_keep_the_status_when_errors_is_of_another_type() {
        expectStatusWithoutMessages(json(HttpStatus.INTERNAL_SERVER_ERROR, "{\"errors\":\"boom\"}"),
            HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void should_keep_the_status_without_a_warning_when_there_is_no_body() {
        expectStatusWithoutMessages(ClientResponse.create(HttpStatus.GATEWAY_TIMEOUT).build(),
            HttpStatus.GATEWAY_TIMEOUT);
        expectStatusWithoutMessages(json(HttpStatus.GATEWAY_TIMEOUT, "  "), HttpStatus.GATEWAY_TIMEOUT);

        assertThat(logged.list).isEmpty();
    }

    //the contract with nothing to say is still the contract: status only, and nothing to warn about
    @Test
    void should_keep_the_status_without_a_warning_when_errors_is_empty_or_null() {
        expectStatusWithoutMessages(json(HttpStatus.CONFLICT, "{\"errors\":[]}"), HttpStatus.CONFLICT);
        expectStatusWithoutMessages(json(HttpStatus.CONFLICT, "{\"errors\":null}"), HttpStatus.CONFLICT);

        assertThat(logged.list).isEmpty();
    }

    //"errors":[null] decodes to a set holding null; the status must not be lost to it
    @Test
    void should_skip_null_messages() {
        DownstreamErrors.read(json(HttpStatus.NOT_FOUND, "{\"errors\":[null,{\"message\":\"m\",\"code\":\"C\"}]}"))
            .as(StepVerifier::create)
            .assertNext(error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                assertThat(error.errors()).extracting(ErrorMessage::getMessage).containsExactly("m");
            })
            .verifyComplete();

        expectStatusWithoutMessages(json(HttpStatus.NOT_FOUND, "{\"errors\":[null]}"), HttpStatus.NOT_FOUND);
    }

    //Spring's own body for a failed binding has an "errors" array too: objects with a code and no
    //message. Read as they are, hasCode("NotBlank") would match a code no ErrorId ever issued
    @Test
    void should_skip_elements_without_a_message() {
        final ClientResponse response = json(HttpStatus.BAD_REQUEST, """
            {"status":400,"errors":[{"field":"name","defaultMessage":"must not be blank","code":"NotBlank"},{},{"message":" "},"text",7]}
            """);

        DownstreamErrors.read(response)
            .as(StepVerifier::create)
            .assertNext(error -> {
                assertThat(error.errors()).isEmpty();
                assertThat(error.hasCode("NotBlank")).isFalse();
            })
            .verifyComplete();
    }

    //one element that cannot be read must not cost the code of the one next to it
    @Test
    void should_keep_readable_messages_next_to_an_unreadable_one() {
        final ClientResponse response = json(HttpStatus.NOT_FOUND, """
            {"errors":[{"message":"ok","code":"X"},{"message":{"nested":1}},{"message":"also ok"}]}
            """);

        DownstreamErrors.read(response)
            .as(StepVerifier::create)
            .assertNext(error -> {
                assertThat(error.errors()).extracting(ErrorMessage::getMessage).containsExactly("ok", "also ok");
                assertThat(error.hasCode("X")).isTrue();
            })
            .verifyComplete();
    }

    @Test
    void should_parse_a_body_already_in_hand_without_ever_throwing() {
        assertThat(DownstreamErrors.messagesOf(WITH_CODE)).extracting(ErrorMessage::getCode)
            .containsExactly("NOT_FOUND_DEVICE_CONFIGURATION");
        assertThat(DownstreamErrors.messagesOf(null)).isEmpty();
        assertThat(DownstreamErrors.messagesOf("")).isEmpty();
        assertThat(DownstreamErrors.messagesOf("<html>")).isEmpty();
        assertThat(DownstreamErrors.messagesOf("{\"errors\":\"boom\"}")).isEmpty();
        assertThat(DownstreamErrors.messagesOf("{\"status\":404}")).isEmpty();
        assertThat(logged.list).isEmpty();
    }

    //the connection is lost while the body is still arriving
    @Test
    void should_keep_the_status_when_the_body_breaks_off() {
        final ClientResponse response = ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(Flux.just(buffer("{\"errors\":[")).concatWith(Flux.error(new IOException("Connection reset"))))
            .build();

        expectStatusWithoutMessages(response, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    //the headers arrived and the body never ends - the shape of the 2026-09-26 outage. Without a
    //bound the status would never reach the caller
    @Test
    void should_keep_the_status_when_the_body_stalls() {
        final ClientResponse response = ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .body(Flux.just(buffer("{\"errors\":[")).concatWith(Flux.never()))
            .build();

        StepVerifier.withVirtualTime(() -> DownstreamErrors.read(response))
            .expectSubscription()
            .expectNoEvent(DownstreamErrors.DEFAULT_BODY_TIMEOUT.minusMillis(1))
            .thenAwait(Duration.ofMillis(2))
            .assertNext(error -> {
                assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                assertThat(error.errors()).isEmpty();
            })
            .verifyComplete();
    }

    @Test
    void should_wait_for_the_body_no_longer_than_asked() {
        final ClientResponse response = ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Flux.<DataBuffer>never())
            .build();

        StepVerifier.withVirtualTime(() -> DownstreamErrors.read(response, Duration.ofMillis(200)))
            .thenAwait(Duration.ofMillis(201))
            .assertNext(error -> assertThat(error.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
            .verifyComplete();
    }

    //a status Spring has no constant for is still a status
    @Test
    void should_keep_a_status_outside_the_known_ones() {
        DownstreamErrors.read(ClientResponse.create(HttpStatusCode.valueOf(599)).build())
            .as(StepVerifier::create)
            .assertNext(error -> {
                assertThat(error.status().value()).isEqualTo(599);
                assertThat(error.httpStatus(HttpStatus.BAD_GATEWAY)).isEqualTo(HttpStatus.BAD_GATEWAY);
            })
            .verifyComplete();
    }

    @Test
    void should_give_the_status_as_http_status_when_spring_knows_it() {
        assertThat(new DownstreamError(HttpStatusCode.valueOf(404), Set.of()).httpStatus(HttpStatus.BAD_GATEWAY))
            .isEqualTo(HttpStatus.NOT_FOUND);
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

    //the order they were read in, the same on every run, and not for the caller to change in place
    @Test
    void should_keep_the_order_of_messages_and_refuse_changes() {
        final Set<ErrorMessage> messages = new LinkedHashSet<>();
        for (final String name : new String[]{"c", "a", "b", "e", "d"}) {
            messages.add(ErrorMessage.builder().message(name).build());
        }
        final DownstreamError error = new DownstreamError(HttpStatus.BAD_GATEWAY, messages);

        assertThat(error.errors()).extracting(ErrorMessage::getMessage).containsExactly("c", "a", "b", "e", "d");
        assertThatThrownBy(() -> error.errors().add(ErrorMessage.builder().message("x").build()))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private void expectStatusWithoutMessages(final ClientResponse response, final HttpStatus status) {
        DownstreamErrors.read(response)
            .as(StepVerifier::create)
            .assertNext(error -> {
                assertThat(error.status()).isEqualTo(status);
                assertThat(error.errors()).isEmpty();
            })
            .verifyComplete();
    }

    private java.util.List<String> warnings() {
        return logged.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    private static DataBuffer buffer(final String text) {
        return DefaultDataBufferFactory.sharedInstance.wrap(text.getBytes(StandardCharsets.UTF_8));
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
