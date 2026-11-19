package dev.jkit.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class VendorTest {

    @Test
    void mapsFriendlyNamesToFoojayAndBack() {
        assertThat(Vendor.of("sapmachine").foojayName()).isEqualTo("sap_machine");
        assertThat(Vendor.of("graalvm-ce").foojayName()).isEqualTo("graalvm_community");
        assertThat(Vendor.of("Temurin")).isEqualTo(new Vendor("temurin", "temurin"));
        assertThat(Vendor.fromFoojay("liberica_native").name()).isEqualTo("liberica-nik");
        assertThat(Vendor.fromFoojay("some_new_vendor").name()).isEqualTo("some-new-vendor");
    }

    @Test
    void knowsGraalVmBasedVendors() {
        assertThat(Vendor.of("mandrel").isGraalVmBased()).isTrue();
        assertThat(Vendor.of("graalvm-ce").isGraalVmBased()).isTrue();
        assertThat(Vendor.of("temurin").isGraalVmBased()).isFalse();
    }

    @Test
    void detectsPlatforms() {
        assertThat(Platform.of("Linux", "amd64")).isEqualTo(Platform.LINUX_X64);
        assertThat(Platform.of("Mac OS X", "aarch64")).isEqualTo(Platform.MACOS_AARCH64);
        assertThat(Platform.of("Windows 11", "amd64")).isEqualTo(Platform.WINDOWS_X64);
    }
}
