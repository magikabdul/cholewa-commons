package cloud.cholewa.commons.logbook;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.ClassUtils;

import java.util.Map;

//The server side of LogbookReactiveAutoConfiguration (HAS-298): Logbook chooses between its Netty
//handler and its WebFilter by the property logbook.reactive.server-mode, read by a condition - so it
//cannot be set by a bean, only by the environment. This post-processor adds the default web-filter as
//the last property source: a consumer that sets the property itself, in a file or a variable, is not
//overridden. Registered in META-INF/spring.factories, which is how Boot finds an
//EnvironmentPostProcessor - the one of org.springframework.boot, the org.springframework.boot.env one
//is deprecated since 4.0 and goes in 4.2; it runs after the configuration files are read (lowest precedence), so
//the switch cholewa.logbook.reactive-filters of the application is already visible here.
//Only an application with Logbook's WebFlux auto-configuration on the classpath is touched; the
//class is checked by name, nothing is loaded
public class LogbookServerModeEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE = "cholewaLogbookDefaults";
    static final String SERVER_MODE = "logbook.reactive.server-mode";
    static final String WEB_FILTER = "web-filter";
    private static final String SWITCH = LogbookReactiveAutoConfiguration.PREFIX + ".reactive-filters";
    private static final String LOGBOOK_WEBFLUX =
        "org.zalando.logbook.autoconfigure.webflux.LogbookWebFluxAutoConfiguration";

    @Override
    public void postProcessEnvironment(final ConfigurableEnvironment environment, final SpringApplication application) {
        if (!ClassUtils.isPresent(LOGBOOK_WEBFLUX, application.getClassLoader())) {
            return;
        }
        //the Binder, not getProperty: the same relaxed names the condition of the auto-configuration
        //accepts, so reactiveFilters: false switches both halves off (review of HAS-298)
        if (!Binder.get(environment).bind(SWITCH, Boolean.class).orElse(true)) {
            return;
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE, Map.of(SERVER_MODE, WEB_FILTER)));
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
