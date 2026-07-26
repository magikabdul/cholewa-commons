package cloud.cholewa.commons.error;

import cloud.cholewa.commons.error.model.Errors;
import cloud.cholewa.commons.error.processor.DataIntegrityViolationExceptionProcessor;
import cloud.cholewa.commons.error.processor.DefaultExceptionProcessor;
import cloud.cholewa.commons.error.processor.DuplicateKeyExceptionProcessor;
import cloud.cholewa.commons.error.processor.ExceptionProcessor;
import cloud.cholewa.commons.error.processor.ResponseStatusExceptionProcessor;
import cloud.cholewa.commons.error.processor.ServerWebInputExceptionProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.webflux.error.DefaultErrorAttributes;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.server.MissingRequestValueException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebInputException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalErrorExceptionHandlerTest {

    private GlobalErrorExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalErrorExceptionHandler(
            new DefaultErrorAttributes(),
            new WebProperties.Resources(),
            new StaticApplicationContext(),
            ServerCodecConfigurer.create()
        );
    }

    @Test
    void should_select_processor_by_exact_class() {
        assertThat(handler.resolveProcessor(new ServerWebInputException("bad input")))
            .isInstanceOf(ServerWebInputExceptionProcessor.class);
    }

    @Test
    void should_select_supertype_processor_for_subclass() {
        MissingRequestValueException exception =
            new MissingRequestValueException("value", String.class, "query parameter", null);

        assertThat(handler.resolveProcessor(exception))
            .isInstanceOf(ServerWebInputExceptionProcessor.class);
    }

    @Test
    void should_select_response_status_processor_for_response_status_exception() {
        assertThat(handler.resolveProcessor(new ResponseStatusException(HttpStatus.NOT_FOUND)))
            .isInstanceOf(ResponseStatusExceptionProcessor.class);
    }

    @Test
    void should_fall_back_to_default_processor_for_unregistered_exception() {
        assertThat(handler.resolveProcessor(new IllegalStateException("boom")))
            .isInstanceOf(DefaultExceptionProcessor.class);
    }

    @Test
    void should_allow_overriding_built_in_processor() {
        ExceptionProcessor override = throwable -> Errors.builder().build();
        handler.withCustomErrorProcessor(Map.of(ServerWebInputException.class, override));

        assertThat(handler.resolveProcessor(new ServerWebInputException("bad input"))).isSameAs(override);
    }

    @Test
    void should_prefer_most_specific_matching_processor() {
        ExceptionProcessor subclassProcessor = throwable -> Errors.builder().build();
        handler.withCustomErrorProcessor(Map.of(MissingRequestValueException.class, subclassProcessor));

        MissingRequestValueException exception =
            new MissingRequestValueException("value", String.class, "query parameter", null) {
            };

        assertThat(handler.resolveProcessor(exception)).isSameAs(subclassProcessor);
    }

    @Test
    void should_select_duplicate_key_processor_for_spring_duplicate_key_exception() {
        assertThat(handler.resolveProcessor(new DuplicateKeyException("boom")))
            .isInstanceOf(DuplicateKeyExceptionProcessor.class);
    }

    @Test
    void should_select_integrity_processor_for_other_data_integrity_violations() {
        assertThat(handler.resolveProcessor(new DataIntegrityViolationException("null value in column")))
            .isInstanceOf(DataIntegrityViolationExceptionProcessor.class);
    }

    @Test
    void should_fall_back_to_default_processor_for_infrastructure_data_access_errors() {
        assertThat(handler.resolveProcessor(new DataAccessResourceFailureException("db down")))
            .isInstanceOf(DefaultExceptionProcessor.class);
    }
}
