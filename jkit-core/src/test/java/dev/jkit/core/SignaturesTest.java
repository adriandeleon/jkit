package dev.jkit.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jkit.model.Vendor;
import java.io.ByteArrayOutputStream;
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
import org.bouncycastle.crypto.generators.RSAKeyPairGenerator;
import org.bouncycastle.crypto.params.RSAKeyGenerationParameters;
import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureGenerator;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentSignerBuilder;
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
        Signatures.Key key = new Signatures.Key("temurin", "Adoptium", "test.asc", fpr);
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

    @Test
    void theBuiltInKeysLoadWithTheirFingerprints() {
        for (Signatures.Key key : Signatures.KEYS) {
            assertThat(Signatures.ring(key).size()).isEqualTo(1);
        }
        assertThat(Signatures.covers(Vendor.of("temurin"))).isTrue();
        assertThat(Signatures.covers(Vendor.of("adoptium"))).isTrue();
        assertThat(Signatures.covers(Vendor.of("microsoft"))).isTrue();
        assertThat(Signatures.covers(Vendor.of("zulu"))).isFalse();
        assertThat(Signatures.covers(null)).isFalse();
    }
}
