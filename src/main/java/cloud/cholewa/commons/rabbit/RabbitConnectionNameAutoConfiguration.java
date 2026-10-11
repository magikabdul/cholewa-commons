package cloud.cholewa.commons.rabbit;

import org.springframework.amqp.rabbit.connection.ConnectionNameStrategy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionMessage;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

//The name the broker shows for a connection: the pod, which already says which service it is and
//tells the old pod from the new one during a rollout. Spring Boot hands the one ConnectionNameStrategy
//bean of the context to the connection factory it configures, so contributing the bean is all it
//takes - and nothing here is a ConnectionFactory or a RabbitOperations, the two types
//RabbitAutoConfiguration backs off from.
//Without spring.application.name there is nothing to name the connection after, and the default
//name of Spring AMQP is left alone
@AutoConfiguration
@ConditionalOnClass(ConnectionNameStrategy.class)
@ConditionalOnProperty(
    prefix = "cholewa.rabbit", name = "pod-connection-name", havingValue = "true", matchIfMissing = true)
@Conditional(RabbitConnectionNameAutoConfiguration.ApplicationNameCondition.class)
public class RabbitConnectionNameAutoConfiguration {

    static final String APPLICATION_NAME = "spring.application.name";
    static final String HOSTNAME = "HOSTNAME";
    static final String LOCAL_SUFFIX = "-local";

    //The bean name is deliberately not "connectionNameStrategy": the services that carried this bean
    //before it moved here call theirs that, and although their bean makes this one back off, a
    //consumer that defines it in a place processed later would otherwise fail on a bean-definition
    //override on the day it upgrades.
    //HOSTNAME is read from the environment and not through a ${HOSTNAME:} placeholder default: an
    //empty value bypasses such a default, and both cases have to end the same way
    @Bean
    @ConditionalOnMissingBean
    ConnectionNameStrategy cholewaPodConnectionNameStrategy(final Environment environment) {
        final String name = connectionName(
            environment.getProperty(HOSTNAME), environment.getRequiredProperty(APPLICATION_NAME));
        return connectionFactory -> name;
    }

    //Kubernetes names a pod after its Deployment and puts that name into HOSTNAME. Anything else
    //found there is not a pod of this service and would not say who is connected: outside a cluster
    //the variable is not simply missing - Git Bash and Linux shells set it to the name of the
    //workstation, plain Docker to a container id
    static String connectionName(final String hostname, final String service) {
        return hostname != null && hostname.startsWith(service + "-") ? hostname : service + LOCAL_SUFFIX;
    }

    //@ConditionalOnProperty takes an empty value for a set one
    static class ApplicationNameCondition extends SpringBootCondition {

        @Override
        public ConditionOutcome getMatchOutcome(final ConditionContext context, final AnnotatedTypeMetadata metadata) {
            final ConditionMessage.Builder message = ConditionMessage.forCondition("Application name");

            return StringUtils.hasText(context.getEnvironment().getProperty(APPLICATION_NAME))
                ? ConditionOutcome.match(message.found("property").items(APPLICATION_NAME))
                : ConditionOutcome.noMatch(message.didNotFind("property").items(APPLICATION_NAME));
        }
    }
}
