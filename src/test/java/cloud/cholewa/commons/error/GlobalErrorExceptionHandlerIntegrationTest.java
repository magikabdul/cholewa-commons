package cloud.cholewa.commons.error;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebInputException;

@WebFluxTest(controllers = GlobalErrorExceptionHandlerIntegrationTest.TestController.class)
@Import({
    GlobalErrorExceptionHandlerIntegrationTest.TestController.class,
    GlobalErrorExceptionHandlerIntegrationTest.TestConfig.class
})
class GlobalErrorExceptionHandlerIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @RestController
    static class TestController {

        @GetMapping("/required-param")
        String requiredParam(@RequestParam final String value) {
            return value;
        }

        @GetMapping("/bad-input")
        String badInput() {
            throw new ServerWebInputException("bad input");
        }

        @GetMapping("/unhandled")
        String unhandled() {
            throw new IllegalStateException("boom");
        }

        @PostMapping("/body")
        String body(@RequestBody final TestPayload payload) {
            return payload.name();
        }

        @GetMapping("/typed/{id}")
        int typed(@PathVariable final int id) {
            return id;
        }

        @GetMapping("/downstream")
        String downstream() {
            throw WebClientResponseException.create(404, "Not Found", HttpHeaders.EMPTY, new byte[0], null);
        }
    }

    record TestPayload(String name) {
    }

    @Configuration
    static class TestConfig {

        @Bean
        GlobalErrorExceptionHandler globalErrorExceptionHandler(
            final ErrorAttributes errorAttributes,
            final ApplicationContext applicationContext,
            final ServerCodecConfigurer configurer
        ) {
            return new GlobalErrorExceptionHandler(
                errorAttributes,
                new WebProperties.Resources(),
                applicationContext,
                configurer
            );
        }
    }

    @Test
    void should_return_bad_request_when_required_param_is_missing() {
        webTestClient.get().uri("/required-param")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].message")
            .isEqualTo("Required query parameter 'value' is not present.");
    }

    @Test
    void should_return_bad_request_for_server_web_input_exception() {
        webTestClient.get().uri("/bad-input")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].message").isEqualTo("bad input");
    }

    @Test
    void should_return_bad_request_when_body_is_missing() {
        webTestClient.post().uri("/body")
            .header("Content-Type", "application/json")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].message").isEqualTo("Missing request body");
    }

    @Test
    void should_return_bad_request_when_body_is_malformed() {
        webTestClient.post().uri("/body")
            .header("Content-Type", "application/json")
            .bodyValue("{not-json")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody().jsonPath("$.errors[0].message").isEqualTo("Malformed request body");
    }

    @Test
    void should_return_bad_request_when_path_variable_type_mismatches() {
        webTestClient.get().uri("/typed/abc")
            .exchange()
            .expectStatus().isBadRequest()
            .expectBody()
            .jsonPath("$.errors[0].message").isEqualTo("Type mismatch.")
            .jsonPath("$.errors[0].details").isEqualTo("For input string: \"abc\"");
    }

    @Test
    void should_return_not_found_for_unmapped_route() {
        webTestClient.get().uri("/nonexistent")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void should_return_method_not_allowed_for_wrong_http_method() {
        webTestClient.post().uri("/bad-input")
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    void should_propagate_downstream_status_for_web_client_response_exception_subclass() {
        webTestClient.get().uri("/downstream")
            .exchange()
            .expectStatus().isNotFound();
    }

    @Test
    void should_return_internal_server_error_for_unhandled_exception() {
        webTestClient.get().uri("/unhandled")
            .exchange()
            .expectStatus().is5xxServerError();
    }
}
