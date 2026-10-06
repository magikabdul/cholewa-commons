package cloud.cholewa.commons.error.model;

public interface ErrorId {

    String getDescription();

    /**
     * The value for {@link ErrorMessage#getCode()}: the name of the constant when the
     * implementation is an enum - which every one is - and nothing otherwise. A name, not the
     * description, because a description is worded for people and gets reworded.
     * <p>
     * Once a caller branches on it, the name of the constant is part of the wire contract:
     * renaming the constant is a breaking change that compiles and passes every test of its own
     * service. Pin the names callers rely on with a test.
     * <p>
     * A static method on purpose, not a default {@code getCode()}: static interface methods are
     * not inherited, so this cannot clash with a {@code getCode()} an implementation already has.
     */
    static String codeOf(final ErrorId errorId) {
        return errorId instanceof Enum<?> constant ? constant.name() : null;
    }
}
