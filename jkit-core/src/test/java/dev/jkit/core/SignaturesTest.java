package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import org.bouncycastle.bcpg.ArmoredOutputStream;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPKeyRingGenerator;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.PGPSignatureSubpacketGenerator;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
import org.bouncycastle.openpgp.operator.bc.BcPGPDigestCalculatorProvider;
import org.bouncycastle.openpgp.operator.bc.BcPGPKeyPair;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SignaturesTest {
    @TempDir
    Path tmp;

    static PGPKeyPair vendor;
    static PGPKeyPair stranger;

    @BeforeAll
    static void keys() throws Exception {
        vendor = rsa();
        stranger = rsa();
    }

    private static PGPKeyPair rsa() throws Exception {
        RSAKeyPairGenerator gen = new RSAKeyPairGenerator();
        gen.init(new RSAKeyGenerationParameters(BigInteger.valueOf(0x10001), new SecureRandom(), 2048, 12));
        return new BcPGPKeyPair(PGPPublicKey.RSA_GENERAL, gen.generateKeyPair(), new Date());
    }

    private static byte[] sign(PGPKeyPair pair, byte[] data, int hash, boolean armored) throws Exception {
        PGPSignatureGenerator gen = new PGPSignatureGenerator(
                new BcPGPContentSignerBuilder(PGPPublicKey.RSA_GENERAL, hash), pair.getPublicKey());
        gen.init(PGPSignature.BINARY_DOCUMENT, pair.getPrivateKey());
        gen.update(data);
        byte[] raw = gen.generate().getEncoded();
        if (!armored) {
            return raw;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ArmoredOutputStream armor = ArmoredOutputStream.builder().build(out)) {
            armor.write(raw);
        }
        return out.toByteArray();
    }

    private Signatures.Result check(byte[] data, byte[] sig) throws Exception {
        Path file = Files.write(tmp.resolve("jdk.tar.gz"), data);
        String fpr = HexFormat.of().withUpperCase().formatHex(vendor.getPublicKey().getFingerprint());
        Signatures.Key key = Signatures.Key.vendor("temurin", "Adoptium", "test.asc", fpr);
        PGPPublicKeyRingCollection ring = new PGPPublicKeyRingCollection(
                List.of(new PGPPublicKeyRing(List.of(vendor.getPublicKey()))));
        return Signatures.verify(file, sig, key, ring);
    }

    private static final byte[] DATA = "a JDK, more or less".getBytes(StandardCharsets.UTF_8);

    @Test
    void aGoodSignatureNamesTheVendor() throws Exception {
        Signatures.Result r = check(DATA, sign(vendor, DATA, HashAlgorithmTags.SHA512, false));
        assertThat(r.outcome()).isEqualTo(Signatures.Outcome.GOOD);
        assertThat(r.signer()).isEqualTo("Adoptium");
        // armored signatures too
        assertThat(check(DATA, sign(vendor, DATA, HashAlgorithmTags.SHA256, true)).outcome())
                .isEqualTo(Signatures.Outcome.GOOD);
    }

    @Test
    void releaseSumsAreCheckedInMemoryAgainstJkitsKey() throws Exception {
        String fpr = HexFormat.of().withUpperCase().formatHex(vendor.getPublicKey().getFingerprint());
        Signatures.Key jkit = new Signatures.Key(null, null, "jkit", "test.asc", List.of(fpr));
        PGPPublicKeyRingCollection ring = new PGPPublicKeyRingCollection(
                List.of(new PGPPublicKeyRing(List.of(vendor.getPublicKey()))));
        byte[] sums = "abc  jkit-9.9.9-linux-x64.tar.gz\n".getBytes(StandardCharsets.UTF_8);
        byte[] sig = sign(vendor, sums, HashAlgorithmTags.SHA256, true);
        assertThat(Signatures.verify(() -> new ByteArrayInputStream(sums), sig, jkit, ring).outcome())
                .isEqualTo(Signatures.Outcome.GOOD);
        byte[] other = "def  jkit-9.9.9-linux-x64.tar.gz\n".getBytes(StandardCharsets.UTF_8);
        assertThat(Signatures.verify(() -> new ByteArrayInputStream(other), sig, jkit, ring).outcome())
                .isEqualTo(Signatures.Outcome.BAD);
        assertThat(Signatures.verify(() -> new ByteArrayInputStream(sums),
                sign(stranger, sums, HashAlgorithmTags.SHA256, true), jkit, ring).outcome())
                .isEqualTo(Signatures.Outcome.UNKNOWN_KEY);
    }

    @Test
    void aSha256sumsSignedByGpgAsTheReleaseWorkflowDoesIsGood() throws Exception {
        // made with gpg --armor --detach-sign --digest-algo SHA512 by an ed25519 test key, as release.yml signs
        Signatures.Key key = new Signatures.Key(null, null, "jkit", "test-release.asc",
                List.of("A8009E6BC1CE7D1F3FE14F791064202FA98AB817"));
        byte[] sums;
        byte[] sig;
        try (InputStream a = SignaturesTest.class.getResourceAsStream("release-SHA256SUMS");
             InputStream b = SignaturesTest.class.getResourceAsStream("release-SHA256SUMS.asc")) {
            sums = a.readAllBytes();
            sig = b.readAllBytes();
        }
        PGPPublicKeyRingCollection ring = Signatures.ring(key);
        assertThat(Signatures.verify(() -> new ByteArrayInputStream(sums), sig, key, ring).outcome())
                .isEqualTo(Signatures.Outcome.GOOD);
    }

    @Test
    void aChangedFileIsBad() throws Exception {
        byte[] sig = sign(vendor, DATA, HashAlgorithmTags.SHA256, false);
        byte[] changed = DATA.clone();
        changed[0] ^= 1;
        assertThat(check(changed, sig).outcome()).isEqualTo(Signatures.Outcome.BAD);
    }

    @Test
    void anotherKeyIsUnknownNotGood() throws Exception {
        Signatures.Result r = check(DATA, sign(stranger, DATA, HashAlgorithmTags.SHA256, false));
        assertThat(r.outcome()).isEqualTo(Signatures.Outcome.UNKNOWN_KEY);
        assertThat(r.detail()).contains("not Adoptium's");
    }

    @Test
    void weakHashesAndJunkAreUnreadable() throws Exception {
        assertThat(check(DATA, sign(vendor, DATA, HashAlgorithmTags.SHA1, false)).outcome())
                .isEqualTo(Signatures.Outcome.UNREADABLE);
        assertThat(check(DATA, "<html>not found</html>".getBytes(StandardCharsets.UTF_8)).outcome())
                .isEqualTo(Signatures.Outcome.UNREADABLE);
        assertThat(check(DATA, new byte[0]).outcome()).isEqualTo(Signatures.Outcome.UNREADABLE);
        assertThat(check(DATA, new byte[Signatures.MAX_SIGNATURE + 1]).outcome())
                .isEqualTo(Signatures.Outcome.UNREADABLE);
    }

    /** A key ring with a user ID and a signing subkey, as Maven's release managers and sbt have. */
    private static PGPPublicKeyRing withSubkey(PGPKeyPair primary, PGPKeyPair subkey, String userId) throws Exception {
        PGPKeyRingGenerator gen = new PGPKeyRingGenerator(PGPSignature.POSITIVE_CERTIFICATION, primary, userId,
                new BcPGPDigestCalculatorProvider().get(HashAlgorithmTags.SHA1), null, null,
                new BcPGPContentSignerBuilder(PGPPublicKey.RSA_GENERAL, HashAlgorithmTags.SHA256), null);
        PGPSignatureSubpacketGenerator signing = new PGPSignatureSubpacketGenerator();
        signing.setKeyFlags(false, KeyFlags.SIGN_DATA);
        gen.addSubKey(subkey, signing.generate(), null);
        return gen.generatePublicKeyRing();
    }

    private static String fingerprint(PGPKeyPair pair) {
        return HexFormat.of().withUpperCase().formatHex(pair.getPublicKey().getFingerprint());
    }

    @Test
    void aToolWithSeveralKeysAcceptsAnyOfThemAndNamesWhoSigned() throws Exception {
        // two release managers; the second signs with a subkey, as Guillaume Nodet and sbt do
        PGPKeyPair subkey = rsa();
        PGPPublicKeyRingCollection ring = new PGPPublicKeyRingCollection(List.of(
                new PGPPublicKeyRing(List.of(stranger.getPublicKey())),
                withSubkey(vendor, subkey, "Release Manager (ASF) <rm@apache.example>")));
        Signatures.Key key = Signatures.Key.tool(Tool.MAVEN, "Apache Maven", "test.asc",
                fingerprint(stranger), fingerprint(vendor));
        Path file = Files.write(tmp.resolve("maven.zip"), DATA);

        Signatures.Result bySubkey = Signatures.verify(file, sign(subkey, DATA, HashAlgorithmTags.SHA512, true), key, ring);
        assertThat(bySubkey.outcome()).isEqualTo(Signatures.Outcome.GOOD);
        assertThat(bySubkey.signer()).isEqualTo("Apache Maven");
        // the person and the primary key, not the subkey
        assertThat(bySubkey.detail()).isEqualTo("Apache Maven (Release Manager, key "
                + fingerprint(vendor).substring(24) + ")");

        assertThat(Signatures.verify(file, sign(stranger, DATA, HashAlgorithmTags.SHA256, false), key, ring).outcome())
                .isEqualTo(Signatures.Outcome.GOOD);

        Signatures.Result unknown = Signatures.verify(file, sign(rsa(), DATA, HashAlgorithmTags.SHA256, false), key, ring);
        assertThat(unknown.outcome()).isEqualTo(Signatures.Outcome.UNKNOWN_KEY);
        assertThat(unknown.detail()).endsWith("not Apache Maven's 2 keys");

        byte[] changed = DATA.clone();
        changed[0] ^= 1;
        Path other = Files.write(tmp.resolve("changed.zip"), changed);
        Signatures.Result bad = Signatures.verify(other, sign(subkey, DATA, HashAlgorithmTags.SHA256, false), key, ring);
        assertThat(bad.outcome()).isEqualTo(Signatures.Outcome.BAD);
        assertThat(bad.detail()).isEqualTo("Apache Maven's key " + fingerprint(vendor).substring(24) + " made it");
    }

    @Test
    void aKeyIsForOneVendorOneToolOrJkit() {
        assertThatThrownBy(() -> new Signatures.Key("temurin", Tool.JDK, "x", "x.asc", List.of("AB")))
                .isInstanceOf(IllegalArgumentException.class);
        // neither is jkit's own release key, which no download lookup finds
        assertThat(Signatures.KEYS).doesNotContain(Signatures.JKIT);
        assertThat(Signatures.ring(Signatures.JKIT).size()).isEqualTo(1); // the built-in key is the one named
        assertThat(Signatures.covers(Tool.JDK, null)).isFalse();
        assertThatThrownBy(() -> Signatures.Key.tool(Tool.SBT, "x", "x.asc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theBuiltInKeysLoadWithTheirFingerprints() {
        for (Signatures.Key key : Signatures.KEYS) {
            assertThat(Signatures.ring(key).size()).as(key.resource()).isEqualTo(key.fingerprints().size());
        }
        assertThat(Signatures.covers(Tool.JDK, Vendor.of("temurin"))).isTrue();
        assertThat(Signatures.covers(Tool.JDK, Vendor.of("adoptium"))).isTrue();
        assertThat(Signatures.covers(Tool.JDK, Vendor.of("microsoft"))).isTrue();
        assertThat(Signatures.covers(Tool.JDK, Vendor.of("corretto"))).isTrue();
        assertThat(Signatures.covers(Tool.JDK, Vendor.of("zulu"))).isFalse();
        assertThat(Signatures.covers(Tool.MAVEN, null)).isTrue();
        assertThat(Signatures.covers(Tool.SBT, null)).isTrue();
        assertThat(Signatures.covers(Tool.GRADLE, null)).isFalse(); // Gradle publishes none
        assertThat(Signatures.covers(Tool.JDK, null)).isFalse();
        assertThat(Signatures.owner(Tool.JDK, Vendor.of("corretto"))).isEqualTo("Amazon");
        assertThat(Signatures.owner(Tool.MAVEN, null)).isEqualTo("Apache Maven");
        assertThat(Signatures.owner(Tool.JDK, Vendor.of("zulu"))).isNull();
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = SignaturesTest.class.getResourceAsStream(name)) {
            return in.readAllBytes();
        }
    }

    /**
     * Real signatures of real downloads, over other bytes: each must be found as the publisher's
     * key and fail as BAD, which shows jkit finds the key (subkeys too) and can run the check
     * (RSA and Ed25519). That they verify the real downloads was checked with gpg.
     */
    @Test
    void realSignaturesAreTiedToTheirPublishersKeys() throws Exception {
        Path file = Files.write(tmp.resolve("not-the-download"), DATA);
        record Case(String resource, Tool tool, Vendor vendor, String detail) { }
        for (Case c : List.of(
                new Case("corretto-21.0.13.1.2.sig", Tool.JDK, Vendor.of("corretto"), "Amazon's key A122542AB04F24E3 made it"),
                new Case("maven-3.9.9.asc", Tool.MAVEN, null, "Apache Maven's key 3A387D43964143E3 made it"),
                new Case("maven-4.0.0-rc-7.asc", Tool.MAVEN, null, "Apache Maven's key ECDFEA3CB4493B94 made it"),
                new Case("sbt-2.1.1.asc", Tool.SBT, null, "sbt's key 99E82A75642AC823 made it"))) {
            Signatures.Result r = Signatures.verify(file, resource(c.resource()), c.tool(), c.vendor());
            assertThat(r.outcome()).as(c.resource()).isEqualTo(Signatures.Outcome.BAD);
            assertThat(r.detail()).as(c.resource()).isEqualTo(c.detail());
        }
        // and a vendor's signature doesn't count for another vendor
        Signatures.Result cross = Signatures.verify(file, resource("corretto-21.0.13.1.2.sig"), Tool.JDK,
                Vendor.of("temurin"));
        assertThat(cross.outcome()).isEqualTo(Signatures.Outcome.UNKNOWN_KEY);
        // Amazon's 2019 builds are signed with keys it has retired: told apart from an unknown key
        Signatures.Result retired = Signatures.verify(file, resource("corretto-11.0.4.11.1.sig"), Tool.JDK,
                Vendor.of("corretto"));
        assertThat(retired.outcome()).isEqualTo(Signatures.Outcome.RETIRED_KEY);
        assertThat(retired.detail()).startsWith("it is signed with Amazon's retired key 5A812B5B67F4FCB4");
        // only for Amazon's builds
        assertThat(Signatures.verify(file, resource("corretto-11.0.4.11.1.sig"), Tool.JDK, Vendor.of("temurin"))
                .outcome()).isEqualTo(Signatures.Outcome.UNKNOWN_KEY);
    }
}
