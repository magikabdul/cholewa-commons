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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.r2dbc.autoconfigure.R2dbcAutoConfiguration;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = R2dbcAutoConfiguration.class)
@ConditionalOnClass(ConnectionFactory.class)
@EnableConfigurationProperties(DatabaseProperties.class)
public class R2dbcConnectionFactoryAutoConfiguration {


    //destroyMethod is explicit: the inferred close() returns a cold Publisher nobody subscribes to,
    //so the pooled connections would survive every shutdown
    @Bean(destroyMethod = "dispose")
    @ConditionalOnMissingBean(ConnectionFactory.class)
    ConnectionFactory connectionFactory(
        DatabaseProperties databaseProperties,
        @Value("${spring.application.name:r2dbc}") String poolName
    ) {
        ConnectionFactory connectionFactory = ConnectionFactories.get(ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.HOST, databaseProperties.host())
            .option(ConnectionFactoryOptions.PORT, databaseProperties.port())
            .option(ConnectionFactoryOptions.DATABASE, databaseProperties.name())
            .option(ConnectionFactoryOptions.USER, databaseProperties.username())
            .option(ConnectionFactoryOptions.PASSWORD, databaseProperties.password())
            .option(Option.valueOf("sslMode"), "REQUIRE")
            .build()
        );

        //R2dbcAutoConfiguration backs off once this bean exists, so without the pool every query -
        //and every /actuator/health call, which the health indicator answers with its own connection -
        //opens a physical connection to a managed database that allows 22 of them in total
        return new ConnectionPool(ConnectionPoolConfiguration.builder(connectionFactory)
            .name(poolName)
            .initialSize(databaseProperties.pool().initialSize())
            .maxSize(databaseProperties.pool().maxSize())
            .maxAcquireTime(databaseProperties.pool().maxAcquireTime())
            .maxIdleTime(databaseProperties.pool().maxIdleTime())
            .build()
        );
    }
}
