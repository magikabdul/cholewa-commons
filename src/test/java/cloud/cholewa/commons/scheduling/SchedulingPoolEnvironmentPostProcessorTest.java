package cloud.cholewa.commons.scheduling;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.context.LifecycleAutoConfiguration;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static cloud.cholewa.commons.scheduling.SchedulingPoolEnvironmentPostProcessor.DEFAULT_POOL_SIZE;
import static cloud.cholewa.commons.scheduling.SchedulingPoolEnvironmentPostProcessor.POOL_SIZE;
import static cloud.cholewa.commons.scheduling.SchedulingPoolEnvironmentPostProcessor.PROPERTY_SOURCE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

//HAS-300: the scheduler of a service gets a pool, so a task that hangs holds one thread and the others
//keep their schedule. The last test is the incident of 2026-10-22 in small, and its control
class SchedulingPoolEnvironmentPostProcessorTest {

    @Test
    void should_be_registered_in_spring_factories() throws IOException {
        final String factories = new ClassPathResource("META-INF/spring.factories")
            .getContentAsString(StandardCharsets.UTF_8);

        assertThat(factories)
            .contains("org.springframework.boot.EnvironmentPostProcessor=")
            .contains(SchedulingPoolEnvironmentPostProcessor.class.getName());
    }

    @Test
    void should_give_the_scheduler_a_pool_when_the_application_sets_none() {
        final StandardEnvironment environment = new StandardEnvironment();

        new SchedulingPoolEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(POOL_SIZE, Integer.class)).isEqualTo(DEFAULT_POOL_SIZE);
        //the last source: anything the application sets later still wins
        assertThat(environment.getPropertySources().stream().toList().getLast().getName()).isEqualTo(PROPERTY_SOURCE);
    }

    @Test
    void should_keep_the_size_the_application_sets() {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application", Map.of(POOL_SIZE, "3")));

        new SchedulingPoolEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(POOL_SIZE, Integer.class)).isEqualTo(3);
        assertThat(environment.getPropertySources().contains(PROPERTY_SOURCE)).isFalse();
    }

    //the way a pod sets it: a variable, under the relaxed name
    @Test
    void should_keep_the_size_a_variable_sets() {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource("pod",
            Map.of("SPRING_TASK_SCHEDULING_POOL_SIZE", "4")));

        new SchedulingPoolEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(POOL_SIZE, Integer.class)).isEqualTo(4);
        assertThat(environment.getPropertySources().contains(PROPERTY_SOURCE)).isFalse();
    }

    //found in the code review: a value that is no number is the application's to fix, and Boot's
    //binding of the property says so - not a stack trace from this post-processor
    @Test
    void should_leave_a_value_that_is_no_number_to_boot() {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application", Map.of(POOL_SIZE, "ten")));

        new SchedulingPoolEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(POOL_SIZE)).isEqualTo("ten");
        assertThat(environment.getPropertySources().contains(PROPERTY_SOURCE)).isFalse();
    }

    //a task that never completes starts first; the other one runs on schedule all the same - the
    //application starts through SpringApplication, so the post-processor comes from spring.factories
    @Test
    void should_keep_the_other_tasks_running_while_one_hangs() {
        try (ConfigurableApplicationContext context = start()) {
            final TwoTasks tasks = context.getBean(TwoTasks.class);

            await().atMost(Duration.ofSeconds(10)).until(() -> tasks.runs.get() >= 3);
        }
    }

    //the control: one thread, as Boot gives a scheduler by default - the hung task takes it and the
    //other one never runs (blinds-service, 2026-10-22)
    @Test
    void should_stop_every_task_behind_a_hung_one_with_a_single_thread() {
        try (ConfigurableApplicationContext context = start(POOL_SIZE + "=1")) {
            final TwoTasks tasks = context.getBean(TwoTasks.class);

            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(2)).until(() -> tasks.runs.get() == 0);
        }
    }

    //the other half: a hung pass bounded by ScheduledPass gives its thread back at its limit, so even
    //one thread serves every task - and the hung task itself runs again
    @Test
    void should_free_the_thread_of_a_hung_pass_at_its_limit() {
        try (ConfigurableApplicationContext context = start(BoundedScheduling.class, POOL_SIZE + "=1")) {
            final BoundedTasks tasks = context.getBean(BoundedTasks.class);

            await().atMost(Duration.ofSeconds(10)).until(() -> tasks.runs.get() >= 3 && tasks.hung.get() >= 2);
        }
    }

    private static ConfigurableApplicationContext start(final String... properties) {
        return start(Scheduling.class, properties);
    }

    private static ConfigurableApplicationContext start(final Class<?> configuration, final String... properties) {
        return new SpringApplicationBuilder(configuration)
            .web(WebApplicationType.NONE)
            //the scheduler waits for its running tasks when the context closes, up to the timeout of
            //the shutdown phase (10 s) - the hung one never ends
            .properties("spring.lifecycle.timeout-per-shutdown-phase=200ms")
            .properties(properties)
            .run();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ImportAutoConfiguration({TaskSchedulingAutoConfiguration.class, LifecycleAutoConfiguration.class})
    static class Scheduling {

        @Bean
        TwoTasks twoTasks() {
            return new TwoTasks();
        }
    }

    static class TwoTasks {

        private final AtomicInteger runs = new AtomicInteger();

        @Scheduled(initialDelay = 0, fixedDelay = 100, timeUnit = TimeUnit.MILLISECONDS)
        Mono<Void> hangs() {
            return Mono.never();
        }

        @Scheduled(initialDelay = 300, fixedDelay = 50, timeUnit = TimeUnit.MILLISECONDS)
        Mono<Void> runs() {
            return Mono.fromRunnable(runs::incrementAndGet);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ImportAutoConfiguration({TaskSchedulingAutoConfiguration.class, LifecycleAutoConfiguration.class})
    static class BoundedScheduling {

        @Bean
        BoundedTasks boundedTasks() {
            return new BoundedTasks();
        }
    }

    static class BoundedTasks {

        private final AtomicInteger hung = new AtomicInteger();
        private final AtomicInteger runs = new AtomicInteger();

        @Scheduled(initialDelay = 0, fixedDelay = 100, timeUnit = TimeUnit.MILLISECONDS)
        Mono<Void> hangs() {
            return ScheduledPass.bounded("hung task", Duration.ofMillis(300), () -> {
                hung.incrementAndGet();
                return Mono.never();
            });
        }

        @Scheduled(initialDelay = 100, fixedDelay = 50, timeUnit = TimeUnit.MILLISECONDS)
        Mono<Void> runs() {
            return ScheduledPass.bounded("counting task", Duration.ofSeconds(1), () -> Mono.fromRunnable(runs::incrementAndGet));
        }
    }
}
