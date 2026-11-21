package dev.jkit.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    }

    @Test
    void looksLikeVersionNeedsALeadingNumber() {
        assertThat(Version.looksLikeVersion("21")).isTrue();
        assertThat(Version.looksLikeVersion(" 8u472 ")).isTrue();
        assertThat(Version.looksLikeVersion("openjdk64-17.0.2")).isFalse();
        assertThat(Version.looksLikeVersion("")).isFalse();
    }
}
