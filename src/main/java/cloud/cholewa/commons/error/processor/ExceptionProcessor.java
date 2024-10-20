package cloud.cholewa.commons.error.processor;

import cloud.cholewa.commons.error.model.Errors;

@FunctionalInterface
public interface ExceptionProcessor {

    Errors apply(final Throwable throwable);
}
