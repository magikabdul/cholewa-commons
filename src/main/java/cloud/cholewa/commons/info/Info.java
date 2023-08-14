package cloud.cholewa.commons.info;

public record Info(
        String name,
        Version version,
        String commitId
) {
}
