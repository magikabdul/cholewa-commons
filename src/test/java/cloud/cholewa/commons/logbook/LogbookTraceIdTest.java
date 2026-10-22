package cloud.cholewa.commons.logbook;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.zalando.logbook.Logbook;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

//The proof of HAS-298, end to end and with the tracing of the services (Micrometer Tracing with
//Brave, spring.reactor.context-propagation: auto): an application calls itself through the
//auto-configured WebClient, and each of the four lines Logbook writes - the request and the response
//on the client, the request and the response on the server - carries the traceId and the spanId of
//the exchange in its MDC, which is what the logstash encoder puts into the JSON line. One trace for
//all four: the traceparent header of the WebClient joins the server to the trace of the client.
//Exactly four lines is the second half of the proof: Logbook's Netty handlers are out, or every
//exchange would be logged twice
@SpringBootTest(
    classes = LogbookTraceIdTest.Application.class,
    webEnvironment = RANDOM_PORT,
    properties = {
        "spring.main.web-application-type=reactive",
        "spring.reactor.context-propagation=auto",
        "logging.level.org.zalando.logbook=TRACE",
        "logbook.format.style=json",
        "management.tracing.sampling.probability=1.0",
        "spring.autoconfigure.exclude=org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration"
    })
class LogbookTraceIdTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class Application {

        @Bean
        RouterFunction<ServerResponse> echo() {
            return route(POST("/echo"), request -> request.bodyToMono(String.class)
                .flatMap(body -> ok().bodyValue("echo: " + body)));
        }
    }

    @Autowired
    private WebClient.Builder webClientBuilder;

    @LocalServerPort
    private int port;

    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private Logger logbookLogger;

    @BeforeEach
    void listenToLogbook() {
        logbookLogger = (Logger) LoggerFactory.getLogger(Logbook.class);
        lines.start();
        logbookLogger.addAppender(lines);
    }

    @AfterEach
    void stopListening() {
        logbookLogger.detachAppender(lines);
        lines.stop();
    }

    @Test
    void should_write_the_trace_of_the_exchange_into_every_logbook_line() {
        final String answer = webClientBuilder.baseUrl("http://localhost:" + port).build()
            .post().uri("/echo")
            .bodyValue("ping")
            .retrieve()
            .bodyToMono(String.class)
            .block(Duration.ofSeconds(10));

        assertThat(answer).isEqualTo("echo: ping");
        //the last line, the response of the client, is written after the body has been handed over
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(lines.list).hasSize(4));

        final List<ILoggingEvent> events = List.copyOf(lines.list);
        final Set<String> kinds = events.stream().map(this::kind).collect(Collectors.toSet());
        assertThat(kinds).containsExactlyInAnyOrder(
            "local request", "remote request", "local response", "remote response");

        for (final ILoggingEvent event : events) {
            final Map<String, String> mdc = event.getMDCPropertyMap();
            assertThat(mdc.get("traceId")).as("traceId of %s", kind(event)).isNotBlank();
            assertThat(mdc.get("spanId")).as("spanId of %s", kind(event)).isNotBlank();
        }
        assertThat(events.stream().map(event -> event.getMDCPropertyMap().get("traceId")).distinct())
            .as("one trace for the four lines")
            .hasSize(1);
    }

    //origin and type of a Logbook JSON line: "local request" is the client sending, "remote request"
    //the server receiving, "local response" the server answering, "remote response" the client reading
    private String kind(final ILoggingEvent event) {
        final JsonNode line = JSON.readTree(event.getFormattedMessage());
        return line.path("origin").asString() + " " + line.path("type").asString();
    }
}
