package cloud.cholewa.commons.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

//No test here opens a connection: Spring AMQP connects with the first use of a template, and
//nothing is published
class NotificationPublisherAutoConfigurationTest {

    private static final String BEAN_NAME = "cholewaNotificationPublisher";

    //the settings of an application whose one connection leads to the notifications
    private static final String[] PUBLISHING_CONNECTION = {
        "spring.rabbitmq.publisher-confirm-type=correlated",
        "spring.rabbitmq.publisher-returns=true",
        "spring.rabbitmq.template.mandatory=true"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            RabbitAutoConfiguration.class, NotificationPublisherAutoConfiguration.class));

    private final ApplicationContextRunner enabled = contextRunner
        .withPropertyValues(PUBLISHING_CONNECTION)
        .withPropertyValues("cholewa.notification.enabled=true", "cholewa.notification.env=dev");

    private final ApplicationContextRunner withoutRabbitAutoConfiguration = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(NotificationPublisherAutoConfiguration.class))
        .withPropertyValues("cholewa.notification.enabled=true", "cholewa.notification.env=dev");

    @Test
    void should_be_listed_as_an_auto_configuration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
            .contains(NotificationPublisherAutoConfiguration.class.getName());
    }

    //a consumer with a broker that publishes no notifications - most of them - gets nothing, and
    //is not asked for an env
    @Test
    void should_stay_off_unless_switched_on() {
        contextRunner.run(context -> assertThat(context)
            .hasNotFailed()
            .doesNotHaveBean(NotificationPublisher.class)
            .doesNotHaveBean(NotificationProperties.class));
    }

    @Test
    void should_stay_off_when_switched_off() {
        enabled
            .withPropertyValues("cholewa.notification.enabled=false")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(NotificationPublisher.class));
    }

    @Test
    void should_publish_over_the_connection_of_the_application() {
        enabled.run(context -> {
            //the bean name is pinned here on purpose - it must stay one no consumer uses
            assertThat(context).hasNotFailed().hasBean(BEAN_NAME);
            //nothing here makes RabbitAutoConfiguration back off: one connection factory and one
            //template, the auto-configured ones
            assertThat(context).hasSingleBean(ConnectionFactory.class).hasSingleBean(RabbitOperations.class);
            assertThat(context.getBean(ConnectionFactory.class)).isInstanceOf(CachingConnectionFactory.class);

            final NotificationPublisher publisher = context.getBean(NotificationPublisher.class);
            assertThat(ReflectionTestUtils.getField(publisher, "rabbitOperations"))
                .isSameAs(context.getBean(RabbitTemplate.class));
            assertThat(ReflectionTestUtils.getField(publisher, "exchange")).isEqualTo("notification");
            assertThat(ReflectionTestUtils.getField(publisher, "env")).isEqualTo("dev");
            assertThat(ReflectionTestUtils.getField(publisher, "confirmTimeout")).isEqualTo(Duration.ofSeconds(10));
        });
    }

    @Test
    void should_take_the_exchange_and_the_timeout_from_the_properties() {
        enabled
            //a bare number is seconds - as milliseconds it would be refused by the lower bound
            .withPropertyValues("cholewa.notification.exchange=household", "cholewa.notification.confirm-timeout=20")
            .run(context -> {
                final NotificationPublisher publisher = context.getBean(NotificationPublisher.class);
                assertThat(ReflectionTestUtils.getField(publisher, "exchange")).isEqualTo("household");
                assertThat(ReflectionTestUtils.getField(publisher, "confirmTimeout"))
                    .isEqualTo(Duration.ofSeconds(20));
            });
    }

    //both packages, not one class: Spring AMQP and the module of Spring Boot that configures it.
    //This hides the classes from the conditions only - the classes of this library are still loaded
    //by the class loader of the test, which has the jars. What a consumer without them really gets
    //is the subject of NoBrokerOnTheClasspathTest
    @ParameterizedTest(name = "cholewa.notification.enabled [{0}]")
    @ValueSource(strings = {"true", "false", ""})
    void should_back_off_when_spring_amqp_is_not_on_the_classpath(final String enabled) {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationPublisherAutoConfiguration.class))
            .withPropertyValues(enabled.isEmpty()
                ? new String[0]
                : new String[] {"cholewa.notification.enabled=" + enabled, "cholewa.notification.env=dev"})
            .withClassLoader(new FilteredClassLoader("org.springframework.amqp", "org.springframework.boot.amqp"))
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(BEAN_NAME));
    }

    //e.g. over a second connection, built with NotificationConnection
    @Test
    void should_back_off_when_the_application_has_a_publisher_of_its_own() {
        enabled
            .withUserConfiguration(OwnPublisherConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(NotificationPublisher.class);
                assertThat(context).hasBean("notificationPublisher").doesNotHaveBean(BEAN_NAME);
            });
    }

    //what heating-service and boiler-service look like on the day they take this release and
    //still carry their own classes: a bean called notificationPublisher of a type of their own
    @Test
    void should_coexist_with_a_bean_of_another_type_called_notification_publisher() {
        enabled
            .withUserConfiguration(LegacyPublisherConfiguration.class)
            .run(context -> assertThat(context)
                .hasNotFailed()
                .hasBean("notificationPublisher")
                .hasBean(BEAN_NAME));
    }

    @Test
    void should_fail_the_startup_when_the_env_is_missing() {
        contextRunner
            .withPropertyValues(PUBLISHING_CONNECTION)
            .withPropertyValues("cholewa.notification.enabled=true")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageContaining("cholewa.notification").hasMessageContaining("env"));
    }

    @Test
    void should_fail_the_startup_when_the_timeout_is_out_of_bounds() {
        enabled
            .withPropertyValues("cholewa.notification.confirm-timeout=500ms")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageContaining("confirmTimeout"));
        enabled
            //10 000 meant as milliseconds: close to three hours
            .withPropertyValues("cholewa.notification.confirm-timeout=10000")
            .run(context -> assertThat(context).hasFailed().getFailure()
                .rootCause().hasMessageContaining("confirmTimeout"));
    }

    @Test
    void should_fail_the_startup_without_correlated_confirms() {
        contextRunner
            .withPropertyValues(
                "spring.rabbitmq.publisher-returns=true",
                "spring.rabbitmq.template.mandatory=true",
                "cholewa.notification.enabled=true",
                "cholewa.notification.env=dev")
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.rabbitmq.publisher-confirm-type: correlated"));
    }

    //simple confirms complete no correlation, so they do not count
    @Test
    void should_fail_the_startup_with_simple_confirms() {
        contextRunner
            .withPropertyValues(
                "spring.rabbitmq.publisher-confirm-type=simple",
                "spring.rabbitmq.publisher-returns=true",
                "spring.rabbitmq.template.mandatory=true",
                "cholewa.notification.enabled=true",
                "cholewa.notification.env=dev")
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessageContaining("spring.rabbitmq.publisher-confirm-type: correlated"));
    }

    @Test
    void should_fail_the_startup_without_publisher_returns() {
        contextRunner
            .withPropertyValues(
                "spring.rabbitmq.publisher-confirm-type=correlated",
                "spring.rabbitmq.template.mandatory=true",
                "cholewa.notification.enabled=true",
                "cholewa.notification.env=dev")
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.rabbitmq.publisher-returns: true"));
    }

    @Test
    void should_fail_the_startup_when_the_template_is_not_mandatory() {
        contextRunner
            .withPropertyValues(
                "spring.rabbitmq.publisher-confirm-type=correlated",
                "spring.rabbitmq.publisher-returns=true",
                "spring.rabbitmq.template.mandatory=false",
                "cholewa.notification.enabled=true",
                "cholewa.notification.env=dev")
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.rabbitmq.template.mandatory: true"));
    }

    //switched on in an application that has no connection to a broker at all: said at the start
    @Test
    void should_fail_the_startup_when_there_is_nothing_to_publish_over() {
        withoutRabbitAutoConfiguration
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("there is no RabbitOperations bean to publish over"));
    }

    //which of the two leads to the notifications is not for a library to guess
    @Test
    void should_fail_the_startup_when_there_are_two_templates_and_none_is_primary() {
        withoutRabbitAutoConfiguration
            .withUserConfiguration(TwoTemplatesConfiguration.class)
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("there are 2 RabbitOperations beans and none is primary"));
    }

    @Test
    void should_publish_over_the_primary_template_when_there_are_two() {
        withoutRabbitAutoConfiguration
            .withUserConfiguration(TwoTemplatesOnePrimaryConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed().hasBean(BEAN_NAME);
                assertThat(ReflectionTestUtils.getField(context.getBean(NotificationPublisher.class), "rabbitOperations"))
                    .isSameAs(context.getBean("notifications"));
            });
    }

    //what a context test of a consumer with a mocked RabbitTemplate meets: the reason, not a
    //NullPointerException
    @Test
    void should_fail_the_startup_with_a_reason_when_the_template_has_no_connection_factory() {
        withoutRabbitAutoConfiguration
            .withUserConfiguration(MockedTemplateConfiguration.class)
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a RabbitOperations with a connection factory, and this one has none"));
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnPublisherConfiguration {

        @Bean
        NotificationPublisher notificationPublisher() {
            return new NotificationPublisher(mock(RabbitOperations.class), "notification", "dev");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoTemplatesConfiguration {

        @Bean
        RabbitOperations notifications() {
            return publishingTemplate();
        }

        @Bean
        RabbitOperations other() {
            return publishingTemplate();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoTemplatesOnePrimaryConfiguration {

        @Bean
        @Primary
        RabbitOperations notifications() {
            return publishingTemplate();
        }

        @Bean
        RabbitOperations other() {
            return publishingTemplate();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class MockedTemplateConfiguration {

        @Bean
        RabbitTemplate rabbitTemplate() {
            return mock(RabbitTemplate.class);
        }
    }

    //not a RabbitTemplate, so the mandatory flag cannot be asked for - the connection factory can
    private static RabbitOperations publishingTemplate() {
        final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        when(connectionFactory.isPublisherConfirms()).thenReturn(true);
        when(connectionFactory.isPublisherReturns()).thenReturn(true);

        final RabbitOperations rabbitOperations = mock(RabbitOperations.class);
        when(rabbitOperations.getConnectionFactory()).thenReturn(connectionFactory);
        return rabbitOperations;
    }

    static class LegacyPublisher {
    }

    @Configuration(proxyBeanMethods = false)
    static class LegacyPublisherConfiguration {

        @Bean
        LegacyPublisher notificationPublisher() {
            return new LegacyPublisher();
        }
    }
}
