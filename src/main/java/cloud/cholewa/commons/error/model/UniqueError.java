package cloud.cholewa.commons.error.model;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public enum UniqueError implements ErrorId {

    NOT_IMPLEMENTED("Logic for this request has not been implemented yet");

    @Getter
    private final String description;
}
