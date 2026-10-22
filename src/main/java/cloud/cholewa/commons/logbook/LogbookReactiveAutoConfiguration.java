package cloud.cholewa.commons.logbook;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.http.client.autoconfigure.reactive.ClientHttpConnectorBuilderCustomizer;
import org.springframework.boot.http.client.reactive.ReactorClientHttpConnectorBuilder;
import org.springframework.boot.webclient.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.autoconfigure.webflux.LogbookWebFluxAutoConfiguration;
import org.zalando.logbook.spring.webflux.LogbookExchangeFilterFunction;

import static org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.REACTIVE;

//Logbook logs an HTTP exchange from inside the reactive chain, so that its lines carry the trace
//(HAS-298). Out of the box Logbook sits in the Netty pipeline - LogbookServerHandler on the server
//(logbook.reactive.server-mode defaults to netty), LogbookClientHandler on the HttpClient of a
//WebClient - and a channel handler runs outside every Reactor operator, where nothing restores the
//MDC: every request and response line of the services had traceId and spanId missing, whatever
//spring.reactor.context-propagation was set to. Logbook ships the same logging as a WebFilter and
//as an ExchangeFilterFunction; both run as operators of the chain, where the context propagation of
//the application puts the span of the exchange back into the MDC before the line is written.
//Two things make it take effect for a consumer that only bumps the library: the server mode is
//defaulted to web-filter by LogbookServerModeEnvironmentPostProcessor (a property, so the consumer's
//own value wins), and the customizer Logbook's auto-configuration would register on Boot's connector
//builder is replaced by one that adds nothing - with both in place every exchange would be logged
//twice. A consumer that adds LogbookClientHandler to an HttpClient of its own has the same double
//logging until it drops that line (every org service did, HAS-298 lists them).
//The switch is cholewa.logbook.reactive-filters; off, Logbook is left exactly as its auto-configuration
//makes it. Provided dependencies and @ConditionalOnClass, like the broker: a consumer without Logbook
//is not touched.
@AutoConfiguration(before = LogbookWebFluxAutoConfiguration.class)
@ConditionalOnClass({Logbook.class, LogbookExchangeFilterFunction.class, WebClient.class, WebClientCustomizer.class})
@ConditionalOnWebApplication(type = REACTIVE)
@ConditionalOnProperty(
    prefix = LogbookReactiveAutoConfiguration.PREFIX, name = "reactive-filters", havingValue = "true", matchIfMissing = true)
public class LogbookReactiveAutoConfiguration {

    static final String PREFIX = "cholewa.logbook";
    //the name Logbook's own auto-configuration checks with @ConditionalOnMissingBean(name = ...)
    static final String NETTY_CLIENT_CUSTOMIZER = "logbookNettyClientCustomizer";

    //every WebClient built from the auto-configured builder logs through the filter; a WebClient built
    //with WebClient.create() does not, and neither does a bare reactor-netty HttpClient
    @Bean
    WebClientCustomizer cholewaLogbookWebClientCustomizer(final Logbook logbook) {
        return builder -> builder.filter(new LogbookExchangeFilterFunction(logbook));
    }

    //Nested and conditional on the names: the customizer type lives in spring-boot-http-client, which
    //a consumer may lack, and a @Bean method whose signature names a missing class fails the whole
    //configuration class. The condition on a nested class is read without loading anything
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
        "org.springframework.boot.http.client.autoconfigure.reactive.ClientHttpConnectorBuilderCustomizer",
        "org.springframework.boot.http.client.reactive.ReactorClientHttpConnectorBuilder"
    })
    static class NoNettyHandlerConfiguration {

        //takes the place of LogbookNettyClientCustomizer, by the name its condition looks for; a
        //connector built by Boot keeps every other customizer and gains no Logbook handler
        @Bean(NETTY_CLIENT_CUSTOMIZER)
        ClientHttpConnectorBuilderCustomizer<ReactorClientHttpConnectorBuilder> logbookNettyClientCustomizer() {
            return builder -> builder;
        }
    }
}
