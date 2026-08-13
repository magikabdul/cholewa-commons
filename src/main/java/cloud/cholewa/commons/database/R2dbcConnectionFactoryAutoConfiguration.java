package cloud.cholewa.commons.database;

import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.ConnectionPoolConfiguration;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration;
import org.springframework.context.annotation.Bean;

//ConnectionPool is guarded as well: it does not come with spring-boot-r2dbc, so a consumer may have
//the SPI without the pool. The property guard keeps the auto-configuration inert for consumers that
//configure their database the Boot way, through spring.r2dbc.*
@AutoConfiguration(before = R2dbcAutoConfiguration.class)
@ConditionalOnClass({ConnectionFactory.class, ConnectionPool.class})
@ConditionalOnProperty(prefix = "database", name = "host")
@EnableConfigurationProperties(DatabaseProperties.class)
public class R2dbcConnectionFactoryAutoConfiguration {

    //destroyMethod is explicit: the inferred close() returns a cold Publisher nobody subscribes to,
    //so the pooled connections would survive every shutdown
    @Bean(destroyMethod = "dispose")
    @ConditionalOnMissingBean
    ConnectionFactory connectionFactory(
        final DatabaseProperties databaseProperties,
        @Value("${spring.application.name:r2dbc}") final String poolName
    ) {
        ConnectionFactory connectionFactory = ConnectionFactories.get(ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.HOST, databaseProperties.host())
            .option(ConnectionFactoryOptions.PORT, databaseProperties.port())
            .option(ConnectionFactoryOptions.DATABASE, databaseProperties.name())
            .option(ConnectionFactoryOptions.USER, databaseProperties.username())
            .option(ConnectionFactoryOptions.PASSWORD, databaseProperties.password())
            .option(Option.valueOf("sslMode"), databaseProperties.sslMode())
            .build()
        );

        //R2dbcAutoConfiguration backs off once this bean exists, so without the pool every query -
        //and every /actuator/health call, which the health indicator answers with its own connection -
        //opens its own physical connection. The pool name only feeds the JMX object name, which is
        //never registered here - the r2dbc_pool_* metrics are tagged with the Spring bean name by
        //ConnectionPoolMetricsAutoConfiguration, so every service reports name="connectionFactory"
        final DatabaseProperties.Pool pool = databaseProperties.pool();

        return new ConnectionPool(ConnectionPoolConfiguration.builder(connectionFactory)
            .name(poolName)
            .initialSize(pool.initialSize())
            .maxSize(pool.maxSize())
            .maxAcquireTime(pool.maxAcquireTime())
            .maxIdleTime(pool.maxIdleTime())
            .build()
        );
    }
}
