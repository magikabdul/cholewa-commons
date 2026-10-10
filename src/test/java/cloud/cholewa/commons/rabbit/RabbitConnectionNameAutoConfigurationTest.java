package cloud.cholewa.commons.rabbit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RabbitConnectionNameAutoConfigurationTest {

    private static final String SERVICE = "heating-service";
    private static final String BEAN_NAME = "cholewaPodConnectionNameStrategy";

    //the properties are set here, above the environment of the machine, so the test reads the
    //same on a workstation, in CI and in a container
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RabbitConnectionNameAutoConfiguration.class))
        .withPropertyValues("spring.application.name=" + SERVICE);

    @Test
    void should_be_listed_as_an_auto_configuration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
            .contains(RabbitConnectionNameAutoConfiguration.class.getName());
    }

    @ParameterizedTest(name = "HOSTNAME [{0}] gives the connection name {1}")
    @CsvSource({
        //a pod of this service: its name, which tells the old pod from the new one in a rollout
        SERVICE + "-5968757896-bghz5, " + SERVICE + "-5968757896-bghz5",
        //a workstation, a container id, a pod of another service, an empty value
        "DESKTOP-4F2K, " + SERVICE + "-local",
        "3f2a9c1b7d4e, " + SERVICE + "-local",
        "other-service-5968757896-bghz5, " + SERVICE + "-local",
        SERVICE + ", " + SERVICE + "-local",
        "'', " + SERVICE + "-local"
    })
    void should_name_the_connection_after_the_pod_and_nothing_else(final String hostname, final String expected) {
        contextRunner
            .withPropertyValues("HOSTNAME=" + hostname)
            .run(context -> {
                //the bean name is pinned here on purpose - it must stay one no consumer uses
                assertThat(context).hasBean(BEAN_NAME);
                assertThat(connectionName(context.getBean(ConnectionNameStrategy.class))).isEqualTo(expected);
            });
    }

    //no HOSTNAME anywhere - the environment of the machine is taken away, because Git Bash, Linux
    //shells and CI runners all set one
    @Test
    void should_name_the_connection_local_when_there_is_no_hostname() {
        contextRunner
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME))
            .run(context -> {
                assertThat(context.getEnvironment().getProperty("HOSTNAME")).isNull();
                assertThat(connectionName(context.getBean(ConnectionNameStrategy.class)))
                    .isEqualTo(SERVICE + "-local");
            });
    }

    @Test
    void should_hand_the_strategy_to_the_connection_factory_spring_boot_configures() {
        contextRunner
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withPropertyValues("HOSTNAME=" + SERVICE + "-5968757896-bghz5")
            .run(context -> {
                //nothing here makes RabbitAutoConfiguration back off: the factory and the template
                //are the auto-configured ones
                assertThat(context).hasSingleBean(ConnectionFactory.class).hasSingleBean(RabbitTemplate.class);

                final CachingConnectionFactory connectionFactory = context.getBean(CachingConnectionFactory.class);
                final ConnectionNameStrategy used = (ConnectionNameStrategy)
                    ReflectionTestUtils.getField(connectionFactory, "connectionNameStrategy");

                assertThat(used).isNotNull();
                assertThat(used.obtainNewConnectionName(connectionFactory))
                    .isEqualTo(SERVICE + "-5968757896-bghz5");
            });
    }

    @Test
    void should_back_off_when_spring_amqp_is_not_on_the_classpath() {
        contextRunner
            .withClassLoader(new FilteredClassLoader(ConnectionNameStrategy.class))
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(BEAN_NAME));
    }

    @Test
    void should_back_off_when_switched_off() {
        contextRunner
            .withPropertyValues("cholewa.rabbit.pod-connection-name=false")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ConnectionNameStrategy.class));
    }

    //what the four services look like on the day they take this release and still carry their bean
    @Test
    void should_back_off_when_the_application_has_a_strategy_of_its_own() {
        contextRunner
            .withUserConfiguration(OwnStrategyConfiguration.class)
            .run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(ConnectionNameStrategy.class);
                assertThat(context).hasBean("connectionNameStrategy").doesNotHaveBean(BEAN_NAME);
                assertThat(connectionName(context.getBean(ConnectionNameStrategy.class))).isEqualTo("own");
            });
    }

    @ParameterizedTest(name = "spring.application.name [{0}]")
    @CsvSource({"''", "'  '"})
    void should_back_off_when_the_application_name_is_blank(final String applicationName) {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitConnectionNameAutoConfiguration.class))
            .withPropertyValues("spring.application.name=" + applicationName)
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ConnectionNameStrategy.class));
    }

    @Test
    void should_back_off_when_the_application_has_no_name() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitConnectionNameAutoConfiguration.class))
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(ConnectionNameStrategy.class));
    }

    private static String connectionName(final ConnectionNameStrategy strategy) {
        return strategy.obtainNewConnectionName(mock(ConnectionFactory.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnStrategyConfiguration {

        @Bean
        ConnectionNameStrategy connectionNameStrategy() {
            return connectionFactory -> "own";
        }
    }
}
