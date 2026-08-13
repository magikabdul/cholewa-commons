package cloud.cholewa.commons.database;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.ConnectionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class R2dbcConnectionFactoryAutoConfigurationTest {

    private static final String[] DATABASE = {
        "database.host=localhost",
        "database.port=5432",
        "database.name=dummyName",
        "database.username=dummyUser",
        "database.password=dummyPassword"
    };

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(R2dbcConnectionFactoryAutoConfiguration.class));

    @Test
    void should_be_listed_as_an_auto_configuration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
            .contains(R2dbcConnectionFactoryAutoConfiguration.class.getName());
    }

    @Test
    void should_create_a_pooled_connection_factory_when_database_is_configured() {
        contextRunner.withPropertyValues(DATABASE).run(context -> {
            assertThat(context).hasSingleBean(ConnectionFactory.class);
            //the bean name is pinned here on purpose - the back-off tests below assert its absence by name
            assertThat(context).hasBean("connectionFactory");
            assertThat(context.getBean(ConnectionFactory.class)).isInstanceOf(ConnectionPool.class);
        });
    }

    @Test
    void should_apply_default_pool_settings_when_the_pool_group_is_absent() {
        contextRunner.withPropertyValues(DATABASE).run(context -> {
            assertThat(context.getBean(DatabaseProperties.class).pool())
                .isEqualTo(new DatabaseProperties.Pool(2, 4, Duration.ofSeconds(10), Duration.ofMinutes(5)));
            assertThat(maxAllocatedSizeOf(context.getBean(ConnectionFactory.class))).isEqualTo(4);
        });
    }

    @Test
    void should_honour_pool_settings_from_configuration() {
        contextRunner
            .withPropertyValues(DATABASE)
            .withPropertyValues(
                "database.pool.initial-size=1",
                "database.pool.max-size=8",
                "database.pool.max-acquire-time=PT3S",
                "database.pool.max-idle-time=PT1M"
            )
            .run(context -> {
                assertThat(context.getBean(DatabaseProperties.class).pool())
                    .isEqualTo(new DatabaseProperties.Pool(1, 8, Duration.ofSeconds(3), Duration.ofMinutes(1)));
                assertThat(maxAllocatedSizeOf(context.getBean(ConnectionFactory.class))).isEqualTo(8);
            });
    }

    @Test
    void should_reject_a_configuration_missing_the_mandatory_properties() {
        contextRunner.withPropertyValues("database.host=localhost").run(context -> {
            assertThat(context).hasFailed();
            //the point of the validation: the failure names the properties, unlike the raw
            //"value must not be null" that ConnectionFactoryOptions would throw
            assertThat(context.getStartupFailure())
                .hasStackTraceContaining("port")
                .hasStackTraceContaining("username");
        });
    }

    @Test
    void should_default_the_ssl_mode_to_require() {
        contextRunner.withPropertyValues(DATABASE).run(context ->
            assertThat(context.getBean(DatabaseProperties.class).sslMode()).isEqualTo("REQUIRE"));
    }

    @Test
    void should_pass_the_ssl_mode_on_to_the_driver() {
        contextRunner
            .withPropertyValues(DATABASE)
            .withPropertyValues("database.ssl-mode=not-a-mode")
            .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void should_back_off_when_the_service_supplies_its_own_connection_factory() {
        contextRunner
            .withPropertyValues(DATABASE)
            .withUserConfiguration(OwnConnectionFactoryConfiguration.class)
            .run(context -> {
                assertThat(context).hasSingleBean(ConnectionFactory.class);
                assertThat(context).hasBean("ownConnectionFactory");
                assertThat(context).doesNotHaveBean("connectionFactory");
            });
    }

    @Test
    void should_back_off_when_the_database_group_is_not_configured() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(ConnectionFactory.class));
    }

    @Test
    void should_back_off_when_the_r2dbc_spi_is_missing() {
        contextRunner
            .withPropertyValues(DATABASE)
            .withClassLoader(new FilteredClassLoader(ConnectionFactory.class))
            .run(context -> assertThat(context).doesNotHaveBean("connectionFactory"));
    }

    @Test
    void should_back_off_when_the_connection_pool_is_missing() {
        contextRunner
            .withPropertyValues(DATABASE)
            .withClassLoader(new FilteredClassLoader(ConnectionPool.class))
            .run(context -> assertThat(context).doesNotHaveBean("connectionFactory"));
    }

    private int maxAllocatedSizeOf(final ConnectionFactory connectionFactory) {
        return ((ConnectionPool) connectionFactory).getMetrics().orElseThrow().getMaxAllocatedSize();
    }

    @Configuration
    static class OwnConnectionFactoryConfiguration {

        @Bean
        ConnectionFactory ownConnectionFactory() {
            return mock(ConnectionFactory.class);
        }
    }
}
