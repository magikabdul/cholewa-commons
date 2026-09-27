package cloud.cholewa.commons.database;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
                .isEqualTo(new DatabaseProperties.Pool(
                    2,
                    4,
                    Duration.ofSeconds(10),
                    Duration.ofMinutes(5),
                    Duration.ofMinutes(30),
                    Duration.ofSeconds(5)
                ));
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
                "database.pool.max-idle-time=PT1M",
                "database.pool.max-life-time=PT10M",
                "database.pool.max-validation-time=PT2S"
            )
            .run(context -> {
                assertThat(context.getBean(DatabaseProperties.class).pool())
                    .isEqualTo(new DatabaseProperties.Pool(
                        1,
                        8,
                        Duration.ofSeconds(3),
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(10),
                        Duration.ofSeconds(2)
                    ));
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
    void should_default_the_connect_timeout_to_ten_seconds() {
        contextRunner.withPropertyValues(DATABASE).run(context ->
            assertThat(context.getBean(DatabaseProperties.class).connectTimeout()).isEqualTo(Duration.ofSeconds(10)));
    }

    @Test
    void should_replace_a_connection_that_does_not_answer_the_validation_query() {
        final Connection hung = connection(Flux.never());
        final Connection healthy = connection(Flux.just(result()));
        final AtomicInteger allocations = new AtomicInteger();
        //the pool calls create() once and subscribes to the result for every allocation, like the
        //cold Mono a real driver returns
        final ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        doReturn(Mono.fromSupplier(() -> allocations.getAndIncrement() == 0 ? hung : healthy))
            .when(connectionFactory).create();

        final ConnectionPool pool = R2dbcConnectionFactoryAutoConfiguration.connectionPool(
            connectionFactory,
            new DatabaseProperties.Pool(
                0, 1, Duration.ofSeconds(10), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofMillis(200)
            ),
            "test"
        );

        try {
            //the incident of 2026-09-26: without validation the pool hands the hung connection out
            //again on every acquire, and every caller queues behind it
            StepVerifier.create(pool.create())
                .expectNextCount(1)
                .verifyComplete();

            verify(hung).close();
            assertThat(allocations).hasValue(2);
        } finally {
            pool.dispose();
        }
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

    private static Connection connection(final Flux<Result> validationResult) {
        final Statement statement = mock(Statement.class);
        doReturn(validationResult).when(statement).execute();

        final Connection connection = mock(Connection.class);
        when(connection.createStatement(R2dbcConnectionFactoryAutoConfiguration.VALIDATION_QUERY)).thenReturn(statement);
        doReturn(Mono.empty()).when(connection).close();
        return connection;
    }

    @SuppressWarnings("unchecked")
    private static Result result() {
        final Result result = mock(Result.class);
        doReturn(Flux.just(1)).when(result).map(any(BiFunction.class));
        return result;
    }

    @Configuration
    static class OwnConnectionFactoryConfiguration {

        @Bean
        ConnectionFactory ownConnectionFactory() {
            return mock(ConnectionFactory.class);
        }
    }
}
