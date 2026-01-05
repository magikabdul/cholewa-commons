package cloud.cholewa.commons.error;

import cloud.cholewa.commons.error.model.Errors;
import cloud.cholewa.commons.error.model.NotImplementedException;
import cloud.cholewa.commons.error.processor.ConstraintViolationExceptionProcessor;
import cloud.cholewa.commons.error.processor.DefaultExceptionProcessor;
import cloud.cholewa.commons.error.processor.DuplicateKeyExceptionProcessor;
import cloud.cholewa.commons.error.processor.ExceptionProcessor;
import cloud.cholewa.commons.error.processor.NoSuchElementProcessor;
import cloud.cholewa.commons.error.processor.NotImplementedExceptionProcessor;
import cloud.cholewa.commons.error.processor.ServerWebInputExceptionProcessor;
import cloud.cholewa.commons.error.processor.WebClientResponseExceptionProcessor;
import cloud.cholewa.commons.error.processor.WebExchangeBindExceptionProcessor;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.webflux.autoconfigure.error.AbstractErrorWebExceptionHandler;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebInputException;
import org.yaml.snakeyaml.constructor.DuplicateKeyException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Order(-2)
public class GlobalErrorExceptionHandler extends AbstractErrorWebExceptionHandler {

    private Map<Class<? extends Exception>, ExceptionProcessor> processors;
    private final ExceptionProcessor defaultExceptionProcessor = new DefaultExceptionProcessor();

    public GlobalErrorExceptionHandler(
        final ErrorAttributes errorAttributes,
        final WebProperties.Resources resources,
        final ApplicationContext applicationContext,
        final ServerCodecConfigurer configurer
    ) {
        super(errorAttributes, resources, applicationContext);
        this.setMessageWriters(configurer.getWriters());

        processors = Map.ofEntries(
            Map.entry(ConstraintViolationException.class, new ConstraintViolationExceptionProcessor()),
            Map.entry(DuplicateKeyException.class, new DuplicateKeyExceptionProcessor()),
            Map.entry(NotImplementedException.class, new NotImplementedExceptionProcessor()),
            Map.entry(ServerWebInputException.class, new ServerWebInputExceptionProcessor()),
            Map.entry(WebClientResponseException.class, new WebClientResponseExceptionProcessor()),
            Map.entry(WebExchangeBindException.class, new WebExchangeBindExceptionProcessor()),
            Map.entry(NoSuchElementException.class, new NoSuchElementProcessor())
        );
    }

    public GlobalErrorExceptionHandler withCustomErrorProcessor(
        final Map<Class<? extends Exception>, ExceptionProcessor> processors
    ) {
        this.processors = Stream.of(this.processors, processors)
            .flatMap(map -> map.entrySet().stream())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return this;
    }

    @Override
    @NullMarked
    protected RouterFunction<ServerResponse> getRoutingFunction(final ErrorAttributes errorAttributes) {
        return RouterFunctions.route(
            RequestPredicates.all(), this::renderErrorResponse
        );
    }

    Mono<ServerResponse> renderErrorResponse(final ServerRequest request) {
        Throwable throwable = getError(request);

        Errors errors = processors.getOrDefault(Objects.requireNonNull(throwable).getClass(), defaultExceptionProcessor)
            .apply(getError(request));

        return ServerResponse
            .status(errors.getHttpStatus())
            .body(BodyInserters.fromValue(errors));
    }

    @Override
    protected void logError(
        final @NonNull ServerRequest request,
        final @NonNull ServerResponse response,
        final @NonNull Throwable throwable
    ) {
        log.error("Received {} error", throwable.getLocalizedMessage());
    }
}
