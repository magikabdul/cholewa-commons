package cloud.cholewa.commons.info;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.info.GitProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ConditionalOnWebApplication
public record InfoController(
        @Value("${application.version}") String version,
        @Value("${application.title}") String name,
        GitProperties gitProperties
) {

    @GetMapping(value = "/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public Info info() {
        return new Info(
                new Application(name, version),
                gitProperties.getCommitId()
        );
    }
}
