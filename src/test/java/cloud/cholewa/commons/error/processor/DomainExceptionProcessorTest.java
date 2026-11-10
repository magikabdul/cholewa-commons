package cloud.cholewa.commons.error.processor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.cholewa.commons.error.model.ErrorId;
import cloud.cholewa.commons.error.model.ErrorMessage;
import cloud.cholewa.commons.error.model.Errors;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

//moved from database-service with HAS-213, behaviour unchanged
class DomainExceptionProcessorTest {

    private static final RuntimeException EXCEPTION = new IllegalStateException("Device not found for point: 7");

    @Getter
    @RequiredArgsConstructor
    private enum TestError implements ErrorId {
        NOT_FOUND_DEVICE_CONFIGURATION("Device configuration not found");

        private final String description;
    }

    //the events themselves, not the console: the text there depends on the encoder the JVM has
    //installed (plain or logstash), the level of an event does not
    private final Logger logger = (Logger) LoggerFactory.getLogger(DomainExceptionProcessor.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    @BeforeEach
    void captureTheLog() {
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void releaseTheLog() {
        logger.detachAppender(logged);
    }

    @Test
    void should_answer_with_the_status_the_description_and_the_name_of_the_cause() {
        final Errors errors = new DomainExceptionProcessor(HttpStatus.NOT_FOUND, TestError.NOT_FOUND_DEVICE_CONFIGURATION)
            .apply(EXCEPTION);

        assertThat(errors.getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(errors.getErrors()).containsExactly(
            ErrorMessage.builder()
                .message("Device configuration not found")
                .details("Device not found for point: 7")
                .code("NOT_FOUND_DEVICE_CONFIGURATION")
                .build()
        );
    }

    //a 4xx at ERROR would feed an error-rate alert for a mistake of the caller
    @Test
    void should_log_a_client_error_as_a_warning_without_a_stack_trace() {
        new DomainExceptionProcessor(HttpStatus.NOT_FOUND, TestError.NOT_FOUND_DEVICE_CONFIGURATION).apply(EXCEPTION);

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage())
                .isEqualTo("Handled [IllegalStateException]: Device not found for point: 7");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    @Test
    void should_log_a_server_error_as_an_error_with_its_stack_trace() {
        new DomainExceptionProcessor(HttpStatus.INTERNAL_SERVER_ERROR, TestError.NOT_FOUND_DEVICE_CONFIGURATION)
            .apply(EXCEPTION);

        assertThat(logged.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage())
                .isEqualTo("Handled [IllegalStateException]: Device not found for point: 7");
            assertThat(event.getThrowableProxy()).isNotNull();
        });
    }

    @Test
    void should_refuse_a_missing_status_or_cause_where_it_is_registered() {
        assertThatNullPointerException()
            .isThrownBy(() -> new DomainExceptionProcessor(null, TestError.NOT_FOUND_DEVICE_CONFIGURATION))
            .withMessage("status");
        assertThatNullPointerException()
            .isThrownBy(() -> new DomainExceptionProcessor(HttpStatus.NOT_FOUND, null))
            .withMessage("cause");
    }

    //a success status would hand the caller an error body it reads as a success
    @Test
    void should_refuse_a_status_that_is_not_an_error() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> new DomainExceptionProcessor(HttpStatus.OK, TestError.NOT_FOUND_DEVICE_CONFIGURATION))
            .withMessage("Not an error status: 200 OK");
    }

    //what failed inside the service is not the caller's to read: the cause is named, the text stays in the log
    @Test
    void should_keep_the_exception_message_of_a_server_error_out_of_the_response() {
        final Errors errors = new DomainExceptionProcessor(HttpStatus.INTERNAL_SERVER_ERROR,
            TestError.NOT_FOUND_DEVICE_CONFIGURATION).apply(EXCEPTION);

        assertThat(errors.getHttpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(errors.getErrors()).containsExactly(
            ErrorMessage.builder()
                .message("Device configuration not found")
                .code("NOT_FOUND_DEVICE_CONFIGURATION")
                .build()
        );
    }
}
