package cloud.cholewa.commons.scheduling;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

//A hung scheduled task must not stop the others (HAS-300). Spring runs a reactive @Scheduled method
//with a fixed delay by subscribing on a thread of the task scheduler and blocking that thread until the
//publisher completes, and Boot's scheduler has one thread by default - so one pass that never completes
//(blinds-service, 2026-10-22: a database call through a connection that died in an outage) stopped
//every scheduled task of the service, with the health UP. With a pool, a hung task holds one thread and
//the others keep their schedule; ScheduledPass.bounded ends the hung one at its limit.
//
//The default is added as the last property source, so an application that sets
//spring.task.scheduling.pool.size itself - in a file or a variable - keeps its value. Registered in
//META-INF/spring.factories, as LogbookServerModeEnvironmentPostProcessor; runs after the configuration
//files are read (lowest precedence). With virtual threads Boot builds a scheduler without a pool, which
//runs every task on a thread of its own: the property is then read by nobody and does no harm
public class SchedulingPoolEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE = "cholewaSchedulingDefaults";
    static final String POOL_SIZE = "spring.task.scheduling.pool.size";
    //more than the scheduled tasks of any org service (light-service has 8, 2026-10-22); an idle
    //thread of a scheduler costs next to nothing
    static final int DEFAULT_POOL_SIZE = 10;

    @Override
    public void postProcessEnvironment(final @NonNull ConfigurableEnvironment environment, final SpringApplication application) {
        //the Binder, not getProperty: the relaxed names Boot binds the property with
        //(SPRING_TASK_SCHEDULING_POOL_SIZE of a pod included)
        if (Binder.get(environment).bind(POOL_SIZE, Integer.class).isBound()) {
            return;
        }
        environment.getPropertySources()
            .addLast(new MapPropertySource(PROPERTY_SOURCE, Map.of(POOL_SIZE, DEFAULT_POOL_SIZE)));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
