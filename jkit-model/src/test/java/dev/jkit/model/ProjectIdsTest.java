package dev.jkit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SDKMAN and asdf/mise version ids, as found in .sdkmanrc and .tool-versions. */
class ProjectIdsTest {

    private static PinnedTool jdk(String version, String vendor, boolean fx) {
        return new PinnedTool(Tool.JDK, version, vendor == null ? null : Vendor.of(vendor), fx);
    }

    @Test
    void readsSdkmanJavaIds() {
        assertThat(SdkmanIds.parse(Tool.JDK, "21.0.5-tem")).isEqualTo(jdk("21.0.5", "temurin", false));
        assertThat(SdkmanIds.parse(Tool.JDK, "17.0.13-amzn")).isEqualTo(jdk("17.0.13", "corretto", false));
        assertThat(SdkmanIds.parse(Tool.JDK, "21.0.5.fx-zulu")).isEqualTo(jdk("21.0.5", "zulu", true));
        assertThat(SdkmanIds.parse(Tool.JDK, "21.0.2-graalce")).isEqualTo(jdk("21.0.2", "graalvm-ce", false));
        assertThat(SdkmanIds.parse(Tool.JDK, "21.0.5-sapmchn").vendor().foojayName()).isEqualTo("sap_machine");
        assertThat(SdkmanIds.parse(Tool.JDK, "8.0.432-librca")).isEqualTo(jdk("8.0.432", "liberica", false));
    }

    @Test
    void sdkmanVersionsMatchInstalledBuildsAsPrefixes() {
        String version = SdkmanIds.parse(Tool.JDK, "21.0.5-tem").version();
        assertThat(Version.parse("21.0.5+11").matches(version)).isTrue();
        assertThat(Version.parse("21.0.6+7").matches(version)).isFalse();
    }

    @Test
    void readsSdkmanBuildToolIds() {
        assertThat(SdkmanIds.tool("maven")).isEqualTo(Tool.MAVEN);
        assertThat(SdkmanIds.tool("sbt")).isEqualTo(Tool.SBT);
        assertThat(SdkmanIds.tool("kotlin")).isNull();
        assertThat(SdkmanIds.parse(Tool.GRADLE, "8.10")).isEqualTo(new PinnedTool(Tool.GRADLE, "8.10", null, false));
    }

    @Test
    void rejectsSdkmanIdsJkitCannotUse() {
        assertThatThrownBy(() -> SdkmanIds.parse(Tool.JDK, "21.0.5"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no vendor suffix");
        assertThatThrownBy(() -> SdkmanIds.parse(Tool.JDK, "21.0.5-nope"))
                .hasMessageContaining("unknown SDKMAN vendor 'nope'");
        assertThatThrownBy(() -> SdkmanIds.parse(Tool.JDK, "22.3.r17-mandrel"))
                .hasMessageContaining("release id");
        assertThatThrownBy(() -> SdkmanIds.parse(Tool.MAVEN, "local")).hasMessageContaining("not a version");
    }

    @Test
    void readsToolVersionsJavaValues() {
        assertThat(ToolVersionsIds.parse(Tool.JDK, "21")).isEqualTo(jdk("21", null, false));
        assertThat(ToolVersionsIds.parse(Tool.JDK, "temurin-21.0.5+11.0.LTS"))
                .isEqualTo(jdk("21.0.5+11.0.LTS", "temurin", false));
        assertThat(ToolVersionsIds.parse(Tool.JDK, "adoptopenjdk-17")).isEqualTo(jdk("17", "temurin", false));
        assertThat(ToolVersionsIds.parse(Tool.JDK, "graalvm-community-21.0.2")).isEqualTo(jdk("21.0.2", "graalvm-ce", false));
        assertThat(ToolVersionsIds.parse(Tool.JDK, "liberica-javafx-21")).isEqualTo(jdk("21", "liberica", true));
        assertThat(ToolVersionsIds.parse(Tool.JDK, "latest")).isEqualTo(jdk(null, null, false));
    }

    @Test
    void readsAsdfAndMiseVendorSpellings() {
        List<String> notes = new ArrayList<>();
        // Corretto's own numbering (version, build, revision) is read as the Java version
        PinnedTool corretto = ToolVersionsIds.parse(Tool.JDK, "corretto-21.0.5.11.1", notes);
        assertThat(corretto).isEqualTo(jdk("21.0.5", "corretto", false));
        assertThat(Version.parse("21.0.5+11").matches(corretto.version())).isTrue();
        assertThat(ToolVersionsIds.parse(Tool.JDK, "corretto-8.432.06.1", notes)).isEqualTo(jdk("8.0.432", "corretto", false));
        // the OpenJ9 release after _openj9- goes
        assertThat(ToolVersionsIds.parse(Tool.JDK, "semeru-openj9-21.0.5+11_openj9-0.48.0", notes))
                .isEqualTo(jdk("21.0.5+11", "semeru", false));
        assertThat(ToolVersionsIds.parse(Tool.JDK, "zulu-javafx-21.0.5", notes)).isEqualTo(jdk("21.0.5", "zulu", true));
        assertThat(ToolVersionsIds.parse(Tool.JDK, "oracle-graalvm-21.0.2", notes)).isEqualTo(jdk("21.0.2", "graalvm", false));
        assertThat(ToolVersionsIds.parse(Tool.JDK, "sapmachine-21.0.5", notes).vendor().foojayName()).isEqualTo("sap_machine");
        assertThat(ToolVersionsIds.parse(Tool.JDK, "microsoft-21.0.5", notes)).isEqualTo(jdk("21.0.5", "microsoft", false));
        PinnedTool mandrel = ToolVersionsIds.parse(Tool.JDK, "mandrel-23.1.5.0-Final-java21", notes);
        assertThat(mandrel).isEqualTo(jdk("21+23.1.5.0", "mandrel", false));
        assertThat(Version.parse("21+23.1.5").matches(mandrel.version())).isTrue();
        assertThat(notes).isEmpty();
    }

    @Test
    void notesWhatItPinsInsteadOfWhatTheFileNames() {
        List<String> notes = new ArrayList<>();
        assertThat(ToolVersionsIds.parse(Tool.JDK, "temurin-jre-21.0.5+11.0.LTS", notes))
                .isEqualTo(jdk("21.0.5+11.0.LTS", "temurin", false));
        assertThat(ToolVersionsIds.parse(Tool.JDK, "liberica-jre-javafx-17", notes)).isEqualTo(jdk("17", "liberica", true));
        assertThat(ToolVersionsIds.parse(Tool.JDK, "zulu-musl-21.0.5", notes)).isEqualTo(jdk("21.0.5", "zulu", false));
        assertThat(ToolVersionsIds.parse(Tool.JDK, "zulu-21.38.21", notes)).isEqualTo(jdk("21", "zulu", false));
        assertThat(ToolVersionsIds.parse(Tool.JDK, "nope-21", notes)).isEqualTo(jdk("21", "nope", false));
        assertThat(notes).containsExactly(
                "'temurin-jre-21.0.5+11.0.LTS' is a JRE; jkit installs full JDKs, pinned as the temurin JDK",
                "'liberica-jre-javafx-17' is a JRE; jkit installs full JDKs, pinned as the liberica JDK",
                "'zulu-musl-21.0.5' asks for a musl build; jkit picks the C library this machine uses",
                "'zulu-21.38.21' is a Zulu release number, which jkit can't match; pinned as zulu 21 "
                        + "(write the Java version, such as zulu-21.0.<update>, to be exact)",
                "'nope-21': jkit doesn't know the vendor 'nope'; it will look for a vendor of that name "
                        + "(jkit search jdk lists them)");
        assertThatThrownBy(() -> ToolVersionsIds.parse(Tool.JDK, "graalvm-22.3.0+java17"))
                .hasMessageContaining("GraalVM release id");
        assertThatThrownBy(() -> ToolVersionsIds.parse(Tool.JDK, "mandrel-23.1.5.0-Final"))
                .hasMessageContaining("not its Java version");
    }

    @Test
    void toolVersionsLtsSuffixStillMatchesTheInstalledBuild() {
        String version = ToolVersionsIds.parse(Tool.JDK, "temurin-21.0.5+11.0.LTS").version();
        assertThat(Version.parse("21.0.5+11").matches(version)).isTrue();
    }

    @Test
    void rejectsToolVersionsValuesJkitCannotUse() {
        assertThatThrownBy(() -> ToolVersionsIds.parse(Tool.JDK, "system")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolVersionsIds.parse(Tool.JDK, "ref:main")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ToolVersionsIds.parse(Tool.MAVEN, "path:/opt/mvn")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ToolVersionsIds.tool("nodejs")).isNull();
        assertThat(ToolVersionsIds.tool("sbt")).isEqualTo(Tool.SBT);
    }

    @Test
    void looksLikeVersionNeedsALeadingNumber() {
        assertThat(Version.looksLikeVersion("21")).isTrue();
        assertThat(Version.looksLikeVersion(" 8u472 ")).isTrue();
        assertThat(Version.looksLikeVersion("openjdk64-17.0.2")).isFalse();
        assertThat(Version.looksLikeVersion("")).isFalse();
    }

    @Test
    void sdkmanEarlyAccessIdsKeepTheBuild() {
        PinnedTool pin = SdkmanIds.parse(Tool.JDK, "26.ea.20-open");
        assertThat(pin.version()).isEqualTo("26-ea+20");
        assertThat(Version.parse(pin.version()).isPreRelease()).isTrue();
        assertThat(Version.parse("26-ea+20").matches(pin.version())).isTrue();
        assertThat(Version.parse("26-ea+19").matches(pin.version())).isFalse();
    }
}
