package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkit.model.JkitException;
import dev.jkit.sources.HttpStatusException;
import dev.jkit.sources.NetworkException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class ErrorsTest {
    private static final URI FOOJAY = URI.create("https://api.foojay.io/disco/v3.0/packages?jdk_version=25");

    @Test
    void httpErrorsNameTheHostNotTheUrl() {
        JkitException e = Errors.describe(new HttpStatusException(400, FOOJAY));
        assertThat(e.getMessage()).isEqualTo("api.foojay.io rejected jkit's request (HTTP 400)");
        assertThat(e.getMessage()).doesNotContain("jdk_version");
        assertThat(Errors.describe(new HttpStatusException(503, FOOJAY)).getMessage())
                .isEqualTo("api.foojay.io is having problems (HTTP 503)");
        assertThat(Errors.describe(new HttpStatusException(429, FOOJAY)).hints())
                .containsExactly("wait a few minutes and try again");
    }

    @Test
    void networkFailuresSayWhatToCheck() {
        assertThat(Errors.describe(new NetworkException(FOOJAY, new UnknownHostException("api.foojay.io"))).getMessage())
                .isEqualTo("can't find api.foojay.io (no DNS answer)");
        JkitException refused = Errors.describe(new NetworkException(FOOJAY, new ConnectException()));
        assertThat(refused.getMessage()).isEqualTo("can't connect to api.foojay.io");
        assertThat(refused.hints()).contains("cached version lists still work with --offline");
    }

    @Test
    void jkitErrorsPassThrough() {
        JkitException original = new JkitException(3, "no installed JDK matches 25");
        assertThat(Errors.describe(original)).isSameAs(original);
    }
}
