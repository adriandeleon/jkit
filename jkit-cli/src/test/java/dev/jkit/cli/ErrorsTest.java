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

    @Test
    void errorMessagesShowTheHomeFolderAsTilde() {
        assertThat(Main.homeAsTilde("/home/ann/p5/jkit.toml:1: invalid TOML", "/home/ann"))
                .isEqualTo("~/p5/jkit.toml:1: invalid TOML");
        assertThat(Main.homeAsTilde("cannot create /home/ann: denied", "/home/ann")).isEqualTo("cannot create ~: denied");
        assertThat(Main.homeAsTilde("in (/home/ann/x)", "/home/ann")).isEqualTo("in (~/x)");
        // on any platform: a Windows home with either slash after it
        assertThat(Main.homeAsTilde("C:\\Users\\ann\\p\\jkit.toml:1: bad", "C:\\Users\\ann"))
                .isEqualTo("~\\p\\jkit.toml:1: bad");
        assertThat(Main.homeAsTilde("C:\\Users\\ann/p/jkit.toml:1: bad", "C:\\Users\\ann"))
                .isEqualTo("~/p/jkit.toml:1: bad");
        // another user's folder, a longer name, or a path inside a word or quotes stays as it is
        assertThat(Main.homeAsTilde("/home/anna/x and /srv/home/ann/x", "/home/ann"))
                .isEqualTo("/home/anna/x and /srv/home/ann/x");
        assertThat(Main.homeAsTilde("chmod o-w '/home/ann/.jkit'", "/home/ann")).isEqualTo("chmod o-w '/home/ann/.jkit'");
    }
}
