package cloud.cholewa.commons.error.model;

public interface ErrorId {

    String getDescription();

    /**
     * The value for {@link ErrorMessage#getCode()}: the name of the constant when the implementation
     * is an enum - which every one is - and nothing otherwise. A name, not the description, because a
     * description is worded for people and gets reworded.
     */
    default String getCode() {
        return this instanceof Enum<?> constant ? constant.name() : null;
    }
}
