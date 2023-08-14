package cloud.cholewa.commons.info;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.GitProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public record InfoController(Info info) {

    public InfoController(
            @Value("${spring.application.version.service}") final String serviceVersion,
            @Value("spring.application.name") final String name,
            final GitProperties gitProperties) {
        this(new Info(
                serviceVersion,
                new Version(name),
                gitProperties.getCommitId()
        ));
    }

    @GetMapping(value = "/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public Info info() {
        return this.info;
    }
}
