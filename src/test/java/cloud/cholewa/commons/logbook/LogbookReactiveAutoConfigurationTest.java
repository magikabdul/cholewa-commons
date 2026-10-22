package cloud.cholewa.commons.logbook;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.http.client.autoconfigure.reactive.ClientHttpConnectorBuilderCustomizer;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder;
import org.springframework.boot.http.client.reactive.ReactorClientHttpConnectorBuilder;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.server.WebFilter;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.autoconfigure.LogbookAutoConfiguration;
import org.zalando.logbook.autoconfigure.webflux.LogbookNettyClientCustomizer;
import org.zalando.logbook.autoconfigure.webflux.LogbookWebFluxAutoConfiguration;
import reactor.netty.http.server.HttpServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static cloud.cholewa.commons.logbook.LogbookReactiveAutoConfiguration.NETTY_CLIENT_CUSTOMIZER;
import static cloud.cholewa.commons.logbook.LogbookServerModeEnvironmentPostProcessor.PROPERTY_SOURCE;
import static cloud.cholewa.commons.logbook.LogbookServerModeEnvironmentPostProcessor.SERVER_MODE;
import static org.assertj.core.api.Assertions.assertThat;

//What the auto-configuration and the post-processor put in place of Logbook's Netty handlers, next
//to the real auto-configurations of Logbook. That the lines then carry the trace is proved end to
//end by LogbookTraceIdTest
class LogbookReactiveAutoConfigurationTest {

    private static final String WEB_CLIENT_CUSTOMIZER = "cholewaLogbookWebClientCustomizer";
    private static final String SERVER_FILTER = "logbookServerFilter";
    private static final String NETTY_SERVER_CUSTOMIZER = "logbookNettyServerCustomizer";

    private final ReactiveWebApplicationContextRunner contextRunner = new ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            LogbookAutoConfiguration.class,
            LogbookWebFluxAutoConfiguration.class,
            LogbookReactiveAutoConfiguration.class));

    @Test
    void should_be_listed_as_an_auto_configuration() {
        assertThat(ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader()).getCandidates())
            .contains(LogbookReactiveAutoConfiguration.class.getName());
    }

    @Test
    void should_register_the_post_processor_in_spring_factories() throws IOException {
        final String factories = new ClassPathResource("META-INF/spring.factories")
            .getContentAsString(StandardCharsets.UTF_8);

        //under the key of org.springframework.boot.EnvironmentPostProcessor: the one of
        //org.springframework.boot.env is deprecated since Boot 4.0 and goes in 4.2
        assertThat(factories)
            .contains("org.springframework.boot.EnvironmentPostProcessor=")
            .doesNotContain("org.springframework.boot.env.EnvironmentPostProcessor")
            .contains(LogbookServerModeEnvironmentPostProcessor.class.getName());
    }

    //the server mode is a property Logbook's conditions read; here it is given as the post-processor
    //gives it, and the WebFilter of Logbook appears in place of its Netty server handler
    @Test
    void should_log_through_the_web_filter_and_the_exchange_filter() {
        contextRunner
            .withPropertyValues(SERVER_MODE + "=web-filter")
            .run(context -> {
                assertThat(context).hasSingleBean(Logbook.class);
                assertThat(context).hasBean(WEB_CLIENT_CUSTOMIZER);
                assertThat(context.getBean(WEB_CLIENT_CUSTOMIZER)).isInstanceOf(WebClientCustomizer.class);
                assertThat(context).hasBean(SERVER_FILTER);
                assertThat(context.getBean(SERVER_FILTER)).isInstanceOf(WebFilter.class);
                assertThat(context).doesNotHaveBean(NETTY_SERVER_CUSTOMIZER);
                //the name Logbook checks is taken, and by something that adds no handler
                assertThat(context.getBean(NETTY_CLIENT_CUSTOMIZER))
                    .isNotInstanceOf(LogbookNettyClientCustomizer.class);
            });
    }

    @Test
    @SuppressWarnings("unchecked")
    void should_leave_a_connector_built_by_boot_without_a_logbook_handler() {
        contextRunner.run(context -> {
            final ClientHttpConnectorBuilderCustomizer<ReactorClientHttpConnectorBuilder> customizer =
                context.getBean(NETTY_CLIENT_CUSTOMIZER, ClientHttpConnectorBuilderCustomizer.class);
            final ReactorClientHttpConnectorBuilder builder = ClientHttpConnectorBuilder.reactor();

            assertThat(customizer.customize(builder)).isSameAs(builder);
        });
    }

    @Test
    void should_leave_logbook_as_its_own_auto_configuration_makes_it_when_switched_off() {
        contextRunner
            .withPropertyValues("cholewa.logbook.reactive-filters=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(WEB_CLIENT_CUSTOMIZER);
                assertThat(context.getBean(NETTY_CLIENT_CUSTOMIZER)).isInstanceOf(LogbookNettyClientCustomizer.class);
                assertThat(context).hasBean(NETTY_SERVER_CUSTOMIZER);
                assertThat(context).doesNotHaveBean(SERVER_FILTER);
            });
    }

    //this auto-configuration alone: Logbook's own are not conditional on the Logbook class and would
    //fail a context that hides it
    @Test
    void should_do_nothing_for_a_consumer_without_logbook() {
        new ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LogbookReactiveAutoConfiguration.class))
            .withClassLoader(new FilteredClassLoader(Logbook.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(WEB_CLIENT_CUSTOMIZER);
                assertThat(context).doesNotHaveBean(NETTY_CLIENT_CUSTOMIZER);
            });
    }

    //the customizer type is in a module of its own; hidden from the conditions, the nested class
    //stays out and the WebClient filter is still there (Logbook's own customizer is not conditional
    //on that type, so it is not asserted away here)
    @Test
    void should_still_add_the_exchange_filter_without_boots_connector_builder() {
        contextRunner
            .withClassLoader(new FilteredClassLoader(ClientHttpConnectorBuilderCustomizer.class))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean(WEB_CLIENT_CUSTOMIZER);
                assertThat(context.getBean(NETTY_CLIENT_CUSTOMIZER)).isInstanceOf(LogbookNettyClientCustomizer.class);
            });
    }

    @Test
    void should_default_the_server_mode_to_the_web_filter() {
        final StandardEnvironment environment = new StandardEnvironment();

        new LogbookServerModeEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(SERVER_MODE)).isEqualTo("web-filter");
        assertThat(environment.getPropertySources().get(PROPERTY_SOURCE)).isNotNull();
    }

    //as a value in application.yaml or a variable: every source of the application comes before
    //the default
    @Test
    void should_let_the_consumers_own_server_mode_win() {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application", Map.of(SERVER_MODE, "netty")));

        new LogbookServerModeEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(SERVER_MODE)).isEqualTo("netty");
    }

    //in either spelling of the key: the relaxed names the condition of the auto-configuration accepts
    @ParameterizedTest
    @ValueSource(strings = {"cholewa.logbook.reactive-filters", "cholewa.logbook.reactiveFilters"})
    void should_not_touch_the_server_mode_when_switched_off(final String key) {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application", Map.of(key, "false")));

        new LogbookServerModeEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(SERVER_MODE)).isNull();
        assertThat(environment.getPropertySources().get(PROPERTY_SOURCE)).isNull();
    }

    @Test
    void should_not_touch_the_server_mode_of_a_consumer_without_logbook() {
        final StandardEnvironment environment = new StandardEnvironment();
        final SpringApplication application = new SpringApplication();
        application.setResourceLoader(new org.springframework.core.io.DefaultResourceLoader(
            new FilteredClassLoader(LogbookWebFluxAutoConfiguration.class, HttpServer.class)));

        new LogbookServerModeEnvironmentPostProcessor().postProcessEnvironment(environment, application);

        assertThat(environment.getProperty(SERVER_MODE)).isNull();
    }
}
