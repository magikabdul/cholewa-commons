package cloud.cholewa.commons.startup;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.StandardEnvironment;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

//The check of the three services it came from (heating-, boiler- and blinds-service), with the list
//of what to check given by the application. The placeholders are written as application.yaml writes
//them: the password of the broker read from a variable that has no default
class RequiredPropertiesAutoConfigurationTest {

    private static final AtomicBoolean BEAN_CREATED = new AtomicBoolean();
    private static final AtomicBoolean ORDERED_POST_PROCESSOR_RAN = new AtomicBoolean();

    private static final String[] TWO_PASSWORDS = {
        "spring.rabbitmq.password=${rabbitmq-password}",
        "notification.password=${notification-rabbitmq-password}",
        "cholewa.required-properties[0].property=spring.rabbitmq.password",
        "cholewa.required-properties[0].variable=rabbitmq-password",
        "cholewa.required-properties[1].property=notification.password",
        "cholewa.required-properties[1].variable=notification-rabbitmq-password"
    };

    //without the variables of the machine: a developer who exported a password for a local run would
    //otherwise see the tests of a missing one fail
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getEnvironment().getPropertySources()
            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
        .withConfiguration(AutoConfigurations.of(RequiredPropertiesAutoConfiguration.class))
        .withUserConfiguration(SomeBean.class);

    @BeforeEach
    void setUp() {
        BEAN_CREATED.set(false);
        ORDERED_POST_PROCESSOR_RAN.set(false);
    }

    @Test
    void should_be_listed_as_an_auto_configuration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
            .contains(RequiredPropertiesAutoConfiguration.class.getName());
    }

    //every consumer that does not list anything, those outside the project included: a password left
    //to its default is nobody's business here
    @Test
    void should_check_nothing_without_the_list() {
        contextRunner
            .withPropertyValues("spring.rabbitmq.password=${rabbitmq-password}")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(BEAN_CREATED).isTrue();
            });
    }

    @Test
    void should_not_start_without_a_listed_property_and_name_what_to_set() {
        contextRunner
            .withPropertyValues(TWO_PASSWORDS)
            .withPropertyValues("notification-rabbitmq-password=secret")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(stackTraceOf(context.getStartupFailure()))
                    .contains("A required property has no value: spring.rabbitmq.password (set rabbitmq-password)")
                    .doesNotContain("notification.password (set");
            });
    }

    //a pod that lacks both is told so in one start, not in two
    @Test
    void should_name_every_property_that_is_missing() {
        contextRunner
            .withPropertyValues(TWO_PASSWORDS)
            .run(context -> assertThat(context.getStartupFailure()).hasStackTraceContaining(
                "A required property has no value: spring.rabbitmq.password (set rabbitmq-password), "
                    + "notification.password (set notification-rabbitmq-password)"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void should_not_start_with_an_empty_value(final String value) {
        contextRunner
            .withPropertyValues(TWO_PASSWORDS)
            .withPropertyValues("rabbitmq-password=" + value, "notification-rabbitmq-password=secret")
            .run(context -> assertThat(context.getStartupFailure())
                .hasStackTraceContaining("A required property has no value: spring.rabbitmq.password"));
    }

    //never set at all - no placeholder, no value
    @Test
    void should_not_start_when_the_property_is_not_there_at_all() {
        contextRunner
            .withPropertyValues(
                "cholewa.required-properties[0].property=spring.rabbitmq.password")
            .run(context -> assertThat(context.getStartupFailure())
                .hasStackTraceContaining("A required property has no value: spring.rabbitmq.password")
                .satisfies(failure -> assertThat(stackTraceOf(failure)).doesNotContain("(set ")));
    }

    //refused before the first bean is created - before a migration is run or a connection opened
    @Test
    void should_refuse_the_start_before_any_bean_is_created() {
        contextRunner
            .withPropertyValues(TWO_PASSWORDS)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(BEAN_CREATED).isFalse();
            });
    }

    //a post-processor of the application runs after the check, even an ordered one: what it might
    //create is not created for a start that is refused
    @Test
    void should_refuse_the_start_before_an_ordered_post_processor_runs() {
        contextRunner
            .withUserConfiguration(OrderedPostProcessor.class)
            .withPropertyValues(TWO_PASSWORDS)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(ORDERED_POST_PROCESSOR_RAN).isFalse();
            });
    }

    //an environment told to ignore unresolvable placeholders hands their text back as the value
    @Test
    void should_not_take_a_placeholder_left_as_text_for_a_value() {
        contextRunner
            .withInitializer(context -> context.getEnvironment().setIgnoreUnresolvableNestedPlaceholders(true))
            .withPropertyValues(TWO_PASSWORDS)
            .withPropertyValues("notification-rabbitmq-password=secret")
            .run(context -> assertThat(context.getStartupFailure())
                .hasStackTraceContaining("A required property has no value: spring.rabbitmq.password (set rabbitmq-password)"));
    }

    @Test
    void should_start_once_every_listed_property_has_a_value() {
        contextRunner
            .withPropertyValues(TWO_PASSWORDS)
            .withPropertyValues("rabbitmq-password=listenerSecret", "notification-rabbitmq-password=publisherSecret")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(BEAN_CREATED).isTrue();
                assertThat(context.getEnvironment().getProperty("spring.rabbitmq.password")).isEqualTo("listenerSecret");
            });
    }

    //the property given itself, above the placeholder of application.yaml
    @Test
    void should_start_with_the_property_given_directly() {
        contextRunner
            .withPropertyValues(TWO_PASSWORDS)
            .withPropertyValues("spring.rabbitmq.password=listenerSecret", "notification.password=publisherSecret")
            .run(context -> assertThat(context).hasNotFailed());
    }

    //a password with "${" in it does not resolve - and must not be quoted by the refusal
    @Test
    void should_not_repeat_a_value_it_cannot_resolve() {
        contextRunner
            .withPropertyValues(TWO_PASSWORDS)
            .withPropertyValues("rabbitmq-password=topSecret${left}Right", "notification-rabbitmq-password=secret")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(stackTraceOf(context.getStartupFailure()))
                    .contains("A required property has no value: spring.rabbitmq.password")
                    .doesNotContain("topSecret", "Right", "left");
            });
    }

    //a list written wrongly is a mistake of the application, said as such
    @Test
    void should_not_start_with_an_entry_that_names_no_property() {
        contextRunner
            .withPropertyValues("cholewa.required-properties[0].variable=rabbitmq-password")
            .run(context -> assertThat(context.getStartupFailure())
                .hasStackTraceContaining("cholewa.required-properties[0].property names no property"));
    }

    private static String stackTraceOf(final Throwable failure) {
        final StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        return trace.toString();
    }

    @Configuration
    static class OrderedPostProcessor {

        @Bean
        static BeanFactoryPostProcessor orderedPostProcessor() {
            return new OrderedBeanFactoryPostProcessor();
        }
    }

    static class OrderedBeanFactoryPostProcessor implements BeanFactoryPostProcessor, PriorityOrdered {

        @Override
        public void postProcessBeanFactory(final ConfigurableListableBeanFactory beanFactory) {
            ORDERED_POST_PROCESSOR_RAN.set(true);
        }

        @Override
        public int getOrder() {
            return 0;
        }
    }

    @Configuration
    static class SomeBean {

        @Bean
        Object someBean() {
            BEAN_CREATED.set(true);
            return new Object();
        }
    }
}
