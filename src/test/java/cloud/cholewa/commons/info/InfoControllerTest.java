package cloud.cholewa.commons.info;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest;
import org.springframework.boot.info.GitProperties;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.mockito.BDDMockito.given;

@WebFluxTest(controllers = InfoController.class)
@Import(InfoController.class)
@TestPropertySource(properties = {
    "application.version=1.0.0",
    "application.title=test-app"
})
class InfoControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private GitProperties gitProperties;

    @Configuration
    static class TestConfig {
    }

    @Test
    void should_return_info() {
        given(gitProperties.getCommitId()).willReturn("abc");

        webTestClient.get().uri("/info")
            .exchange()
            .expectStatus().isOk();
    }
}
