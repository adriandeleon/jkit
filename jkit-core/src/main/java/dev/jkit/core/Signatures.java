package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.Vendor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;

/**
 * OpenPGP signatures of vendor downloads, checked against keys built into jkit: one per vendor,
 * so a Temurin download must be signed by Adoptium's key, not by any key jkit knows. Only vendors
 * that publish a signature foojay lists are covered. A signature is checked on top of the
 * checksum, never instead of it.
 */
public final class Signatures {

    /**
     * A vendor's signing key: the {@code .asc} resource under {@code dev/jkit/core/keys/} and its
     * primary key's fingerprint, checked when the key is loaded.
     */
    record Key(String foojayName, String owner, String resource, String fingerprint) { }

    /**
     * Each key was taken from where the vendor's own documentation points (Adoptium: its GPG
     * verification page and keyserver.ubuntu.com; Microsoft: the public key linked from the
     * Microsoft Build of OpenJDK download page) and checked by verifying real downloads with it.
     */
    static final List<Key> KEYS = List.of(
            new Key("temurin", "Adoptium", "adoptium.asc", "3B04D753C9050D9A5D343F39843C48A565F8F04B"),
            new Key("microsoft", "Microsoft", "microsoft.asc", "B602433384B8991302924D8235531D315B21C189"));

    /** A signature file is a few hundred bytes; anything this big isn't one. */
    static final int MAX_SIGNATURE = 64 * 1024;

    private static final Set<Integer> STRONG_HASHES = Set.of(HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA384,
            HashAlgorithmTags.SHA512, HashAlgorithmTags.SHA3_256, HashAlgorithmTags.SHA3_512);

    public enum Outcome {
        /** Signed by the vendor's key: {@link Result#signer} says whose. */
        GOOD,
        /** The vendor's key made it, and it doesn't match the file: refuse the download. */
        BAD,
        /** Not by a key jkit has for this vendor (the vendor may have a new key): checksum only. */
        UNKNOWN_KEY,
        /** Not a signature jkit can check (damaged, or a weak hash): checksum only. */
        UNREADABLE
    }

    /** @param detail the signer's name and key for GOOD, otherwise why it isn't a good signature */
    public record Result(Outcome outcome, String signer, String detail) { }

    private Signatures() { }

    /** Whether jkit has a key for {@code vendor}'s downloads. */
    public static boolean covers(Vendor vendor) {
        return key(vendor) != null;
    }

    private static Key key(Vendor vendor) {
        if (vendor == null) {
            return null;
        }
        return KEYS.stream().filter(k -> k.foojayName().equals(vendor.foojayName())).findFirst().orElse(null);
    }

    /** Checks {@code signature} over {@code file} against {@code vendor}'s key. */
    public static Result verify(Path file, byte[] signature, Vendor vendor) throws IOException {
        Key key = key(vendor);
        if (key == null) {
            throw new IllegalArgumentException("no key for " + vendor);
        }
        return verify(file, signature, key, ring(key));
    }

    /** {@link #verify(Path, byte[], Vendor)} with the key ring given: tests sign with a key of their own. */
    static Result verify(Path file, byte[] signature, Key key, PGPPublicKeyRingCollection ring) throws IOException {
        if (signature.length > MAX_SIGNATURE) {
            return new Result(Outcome.UNREADABLE, null, "the signature file is " + signature.length + " bytes");
        }
        PGPSignatureList signatures;
        try {
            signatures = signatures(signature);
        } catch (IOException | RuntimeException e) {
            return new Result(Outcome.UNREADABLE, null, "not an OpenPGP signature");
        }
        if (signatures == null || signatures.isEmpty()) {
            return new Result(Outcome.UNREADABLE, null, "not an OpenPGP signature");
        }
        String others = null;
        for (PGPSignature sig : signatures) {
            PGPPublicKey signer = ring.getPublicKey(sig.getKeyID());
            if (signer == null) {
                others = "made with key " + keyId(sig.getKeyID()) + ", not " + key.owner() + "'s "
                        + longId(key.fingerprint());
                continue;
            }
            if (sig.getSignatureType() != PGPSignature.BINARY_DOCUMENT || !STRONG_HASHES.contains(sig.getHashAlgorithm())) {
                return new Result(Outcome.UNREADABLE, null, "a signature of a kind jkit doesn't accept (type "
                        + sig.getSignatureType() + ", hash " + sig.getHashAlgorithm() + ")");
            }
            if (signer.hasRevocation()) {
                return new Result(Outcome.UNKNOWN_KEY, null, key.owner() + "'s key " + longId(key.fingerprint())
                        + " is revoked");
            }
            try {
                sig.init(new BcPGPContentVerifierBuilderProvider(), signer);
                try (InputStream in = Files.newInputStream(file)) {
                    byte[] buffer = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        sig.update(buffer, 0, n);
                    }
                }
                if (sig.verify()) {
                    return new Result(Outcome.GOOD, key.owner(), key.owner() + " (key " + longId(key.fingerprint()) + ")");
                }
                return new Result(Outcome.BAD, null, key.owner() + "'s key " + longId(key.fingerprint()) + " made it");
            } catch (PGPException e) {
                return new Result(Outcome.UNREADABLE, null, "it can't be checked (" + e.getMessage() + ")");
            }
        }
        return new Result(Outcome.UNKNOWN_KEY, null, others);
    }

    private static PGPSignatureList signatures(byte[] signature) throws IOException {
        // .sig files come binary (Temurin) or ASCII-armored; the decoder stream takes both
        BcPGPObjectFactory factory = new BcPGPObjectFactory(PGPUtil.getDecoderStream(new ByteArrayInputStream(signature)));
        Object first = factory.nextObject();
        if (first instanceof PGPCompressedData compressed) {
            try {
                factory = new BcPGPObjectFactory(compressed.getDataStream());
            } catch (PGPException e) {
                throw new IOException(e);
            }
            first = factory.nextObject();
        }
        return first instanceof PGPSignatureList list ? list : null;
    }

    /** The key ring for {@code key}, checked to hold exactly the fingerprint jkit expects. */
    static PGPPublicKeyRingCollection ring(Key key) {
        String resource = "keys/" + key.resource();
        try (InputStream in = Signatures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new JkitException("jkit is missing its built-in key " + resource);
            }
            PGPPublicKeyRingCollection ring = new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(in),
                    new BcKeyFingerprintCalculator());
            PGPPublicKey primary = ring.getKeyRings().next().getPublicKey();
            String fingerprint = HexFormat.of().withUpperCase().formatHex(primary.getFingerprint());
            if (ring.size() != 1 || !fingerprint.equals(key.fingerprint())) {
                throw new JkitException("jkit's built-in key " + resource + " isn't " + key.fingerprint());
            }
            return ring;
        } catch (IOException | PGPException e) {
            throw new JkitException("jkit can't read its built-in key " + resource + ": " + e.getMessage());
        }
    }

    private static String keyId(long id) {
        return String.format(Locale.ROOT, "%016X", id);
    }

    /** The last 16 hex digits, as gpg shows a long key ID. */
    private static String longId(String fingerprint) {
        return fingerprint.substring(fingerprint.length() - 16);
    }
}
