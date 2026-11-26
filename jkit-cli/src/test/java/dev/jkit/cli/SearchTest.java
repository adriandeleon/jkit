package dev.jkit.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkit.model.ToolSpec;
import dev.jkit.model.Vendor;
import dev.jkit.model.Version;
import dev.jkit.sources.FoojaySource.Availability;
import dev.jkit.sources.FoojaySource.Pkg;
import java.net.URI;
import org.junit.jupiter.api.Test;

class SearchTest {

    private static Pkg pkg(String vendor) {
        return new Pkg("id", Vendor.of(vendor), 21, Version.parse("21.0.13"), "jdk.tar.gz", "tar.gz", "lts", true);
    }

    private static SearchCommand.Verdict verdict(Availability a) {
        SearchCommand cmd = new SearchCommand();
        cmd.spec = ToolSpec.parse("jdk@21");
        return cmd.verdict(a);
    }

    @Test
    void searchMarksWhatInstallWouldRefuse() {
        URI sig = URI.create("https://example.invalid/jdk.tar.gz.sig");
        // a checksum: installs
        assertThat(verdict(new Availability(pkg("zulu"), true, null))).isEqualTo(SearchCommand.Verdict.OK);
        // no checksum, but a signature by a key jkit has (Corretto): installs
        assertThat(verdict(new Availability(pkg("corretto"), false, sig))).isEqualTo(SearchCommand.Verdict.OK);
        // no checksum and no signature jkit can check: needs --allow-unverified
        assertThat(verdict(new Availability(pkg("dragonwell"), false, null)))
                .isEqualTo(SearchCommand.Verdict.UNVERIFIED);
        assertThat(verdict(new Availability(pkg("dragonwell"), false, sig)))
                .isEqualTo(SearchCommand.Verdict.UNVERIFIED);
        // Amazon's 2019 builds, on its old CloudFront host: signed with keys it has retired
        URI retired = URI.create("https://d3pxv6yz143wms.cloudfront.net/8.232.09.1/jdk.tar.gz.sig");
        assertThat(verdict(new Availability(pkg("corretto"), false, retired)))
                .isEqualTo(SearchCommand.Verdict.UNVERIFIED);
        // no direct download
        assertThat(verdict(new Availability(null, false, null))).isEqualTo(SearchCommand.Verdict.NO_DOWNLOAD);
    }
}
