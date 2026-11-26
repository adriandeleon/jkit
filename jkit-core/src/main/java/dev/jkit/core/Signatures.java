package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPUtil;
import org.bouncycastle.openpgp.bc.BcPGPObjectFactory;
import org.bouncycastle.openpgp.operator.bc.BcKeyFingerprintCalculator;
import org.bouncycastle.openpgp.operator.bc.BcPGPContentVerifierBuilderProvider;

/**
 * OpenPGP signatures of downloads, checked against keys built into jkit and tied to who publishes
 * the download: a Temurin download must be signed by Adoptium's key, a Maven zip by one of the
 * Maven release managers' keys, not by any key jkit knows. A signature is checked on top of the
 * checksum, except for Corretto, which publishes none: there the signature is the only check.
 */
public final class Signatures {

    /**
     * The signing keys of one publisher: the {@code .asc} resource under {@code dev/jkit/core/keys/}
     * and the fingerprints of the primary keys it must hold, exactly, checked when it is loaded.
     *
     * @param vendor the foojay name of the JDK vendor whose builds these keys sign, or null
     * @param tool the tool whose downloads these keys sign, when {@code vendor} is null
     */
    record Key(String vendor, Tool tool, String owner, String resource, List<String> fingerprints) {
        Key {
            // neither: jkit's own release key (JKIT), which signs jkit's releases, not downloads
            if ((vendor != null && tool != null) || fingerprints.isEmpty()) {
                throw new IllegalArgumentException("a key is for one vendor, one tool, or jkit");
            }
            fingerprints = List.copyOf(fingerprints);
        }

        static Key vendor(String foojayName, String owner, String resource, String... fingerprints) {
            return new Key(foojayName, null, owner, resource, List.of(fingerprints));
        }

        static Key tool(Tool tool, String owner, String resource, String... fingerprints) {
            return new Key(null, tool, owner, resource, List.of(fingerprints));
        }

        /** "Adoptium's key 843C48A565F8F04B", or "Apache Maven's 4 keys". */
        String described() {
            return fingerprints.size() == 1 ? owner + "'s key " + longId(fingerprints.getFirst())
                    : owner + "'s " + fingerprints.size() + " keys";
        }
    }

    /**
     * Each key was taken from where the publisher's own documentation points and checked by
     * verifying real downloads with it:
     * <ul>
     * <li>Adoptium: its GPG verification page and keyserver.ubuntu.com.
     * <li>Microsoft: the public key linked from the Microsoft Build of OpenJDK download page.
     * <li>Amazon: the key linked from the "Signature Verification" section of the Corretto
     *     downloads page (the same key as apt.corretto.aws/corretto.key). It signs every Corretto
     *     build from January 2020 on (8u242, 11.0.6), in every line from 8 to the newest; the 2019
     *     builds before those are signed with {@link #RETIRED} keys.
     * <li>Apache Maven: Maven's KEYS file (downloads.apache.org/maven/KEYS). Each release is signed
     *     by whichever release manager cut it; these are the four who signed 3.9.0 and later
     *     (Tamas Cservenak's two keys, Slawomir Jaranowski, Guillaume Nodet with his signing
     *     subkeys). A release by someone else is "not a key jkit has" until jkit adds theirs.
     * <li>sbt: the "sbt build tool" key named on sbt's install page; its signing subkey
     *     05B370DC1261451962FFE35ACD4EAA5906F9BF46 signs releases from 1.12.2 on.
     * </ul>
     */
    static final List<Key> KEYS = List.of(
            Key.vendor("temurin", "Adoptium", "adoptium.asc", "3B04D753C9050D9A5D343F39843C48A565F8F04B"),
            Key.vendor("microsoft", "Microsoft", "microsoft.asc", "B602433384B8991302924D8235531D315B21C189"),
            Key.vendor("corretto", "Amazon", "corretto.asc", "6DC3636DAE534049C8B94623A122542AB04F24E3"),
            Key.tool(Tool.MAVEN, "Apache Maven", "apache-maven.asc",
                    "29BEA2A645F2D6CED7FB12E02B172E3E156466E8",  // Tamas Cservenak (ASF release key)
                    "88BE34F94BDB2B5357044E2E3A387D43964143E3",  // Tamas Cservenak
                    "84789D24DF77A32433CE1F079EB80E92EB2135B1",  // Slawomir Jaranowski
                    "EA23DB1360D9029481E7F2EFECDFEA3CB4493B94"), // Guillaume Nodet
            Key.tool(Tool.SBT, "sbt", "sbt.asc", "2EE0EA64E40A89B84B2DF73499E82A75642AC823"));

    /**
     * Keys a publisher has stopped using, by long key ID, so that a signature made with one is
     * told apart from one by a key jkit doesn't know yet. Amazon signed each of its 2019 Corretto
     * releases (8u222, 8u232, 11.0.4, 11.0.5; foojay still lists them) with a key of its own: SHA-1
     * signatures, by RSA keys that expired in 2024. jkit has none of them, and a newer jkit won't
     * either.
     */
    static final Map<String, Set<String>> RETIRED = Map.of("corretto", Set.of(
            "BD93DF06B540D62A", "E9B1F93E1A158134", // 8.222.10.1 to .3, 8.222.10.4
            "9CB3BC6FF6C9FC19", "1D03C279C44F4D36", // 8.232.09.1, 8.232.09.2
            "5A812B5B67F4FCB4", "12EABAC5209DDBC0", "FF41EA72B3E42D5D")); // 11.0.4.11.1, 11.0.5.10.1, 11.0.5.10.2

    /** The primary fingerprint of jkit's release key; release.yml checks the signing key against it too. */
    public static final String JKIT_FINGERPRINT = "A9444E8F5048FEBCB942E110FC89EBE68C4539E6";

    /**
     * jkit's own release key: the release workflow signs each release's SHA256SUMS with it
     * (SHA256SUMS.asc), and jkit self-update checks that signature before it trusts the sums.
     * Not in {@link #KEYS}: it signs nothing jkit installs.
     */
    static final Key JKIT = new Key(null, null, "jkit", "jkit-release.asc", List.of(JKIT_FINGERPRINT));

    /** A signature file is a few hundred bytes; anything this big isn't one. */
    static final int MAX_SIGNATURE = 64 * 1024;

    private static final Set<Integer> STRONG_HASHES = Set.of(HashAlgorithmTags.SHA256, HashAlgorithmTags.SHA384,
            HashAlgorithmTags.SHA512, HashAlgorithmTags.SHA3_256, HashAlgorithmTags.SHA3_512);

    public enum Outcome {
        /** Signed by the publisher's key: {@link Result#signer} says whose. */
        GOOD,
        /** The publisher's key made it, and it doesn't match the file: refuse the download. */
        BAD,
        /** Not by a key jkit has for this publisher (it may have a new key): checksum only. */
        UNKNOWN_KEY,
        /** By one of the publisher's {@link #RETIRED} keys, which jkit doesn't have: checksum only. */
        RETIRED_KEY,
        /** Not a signature jkit can check (damaged, or a weak hash): checksum only. */
        UNREADABLE
    }

    /** @param detail the signer's name and key for GOOD, otherwise why it isn't a good signature */
    public record Result(Outcome outcome, String signer, String detail) { }

    private Signatures() { }

    /**
     * Whether jkit has keys for these downloads: {@code vendor}'s JDK builds, or, with no vendor,
     * {@code tool}'s.
     */
    public static boolean covers(Tool tool, Vendor vendor) {
        return key(tool, vendor) != null;
    }

    /** Whose keys check these downloads ("Amazon"), or null when jkit has none. */
    public static String owner(Tool tool, Vendor vendor) {
        Key key = key(tool, vendor);
        return key == null ? null : key.owner();
    }

    private static Key key(Tool tool, Vendor vendor) {
        return KEYS.stream()
                .filter(k -> vendor != null ? vendor.foojayName().equals(k.vendor()) : k.tool() != null && k.tool() == tool)
                .findFirst().orElse(null);
    }

    /** Checks {@code signature} over {@code file} against the keys for {@code tool} or {@code vendor}. */
    public static Result verify(Path file, byte[] signature, Tool tool, Vendor vendor) throws IOException {
        Key key = key(tool, vendor);
        if (key == null) {
            throw new IllegalArgumentException("no key for " + tool + " " + vendor);
        }
        return verify(file, signature, key, ring(key));
    }

    /** Checks {@code signature} over {@code data}, a jkit release's SHA256SUMS, against jkit's own release key. */
    public static Result verifyRelease(byte[] data, byte[] signature) throws IOException {
        return verify(() -> new ByteArrayInputStream(data), signature, JKIT, ring(JKIT));
    }

    /** {@link #verify(Path, byte[], Tool, Vendor)} with the key ring given: tests sign with keys of their own. */
    static Result verify(Path file, byte[] signature, Key key, PGPPublicKeyRingCollection ring) throws IOException {
        return verify(() -> Files.newInputStream(file), signature, key, ring);
    }

    /** What a signature is over: opened once per signature tried. */
    @FunctionalInterface
    interface Data {
        InputStream open() throws IOException;
    }

    /**
     * What can be told of {@code signature} without the file it is over: null when a key jkit has
     * for {@code tool} or {@code vendor} made it and it is of a kind jkit accepts, so checking the
     * file decides; otherwise why it can't be checked (never GOOD or BAD). An install asks this
     * before it downloads, so a signature it would refuse anyway doesn't cost the whole download.
     */
    public static Result precheck(byte[] signature, Tool tool, Vendor vendor) throws IOException {
        Key key = key(tool, vendor);
        if (key == null) {
            throw new IllegalArgumentException("no key for " + tool + " " + vendor);
        }
        return verify((Data) null, signature, key, ring(key));
    }

    /** With {@code data} null, stops where the file would be read and returns null ({@link #precheck}). */
    static Result verify(Data data, byte[] signature, Key key, PGPPublicKeyRingCollection ring) throws IOException {
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
        String retired = null;
        for (PGPSignature sig : signatures) {
            PGPPublicKey signer = ring.getPublicKey(sig.getKeyID());
            if (signer == null) {
                String id = keyId(sig.getKeyID());
                if (key.vendor() != null && RETIRED.getOrDefault(key.vendor(), Set.of()).contains(id)) {
                    retired = "it is signed with " + key.owner() + "'s retired key " + id
                            + ", one of the keys of its 2019 releases, which expired in 2024";
                }
                others = "made with key " + id + ", not " + key.described();
                continue;
            }
            // the signing key may be a subkey (sbt, Maven): name the primary key it belongs to
            PGPPublicKey primary = ring.getPublicKeyRing(sig.getKeyID()).getPublicKey();
            String whose = key.owner() + "'s key " + longId(fingerprint(primary));
            if (sig.getSignatureType() != PGPSignature.BINARY_DOCUMENT || !STRONG_HASHES.contains(sig.getHashAlgorithm())) {
                return new Result(Outcome.UNREADABLE, null, "a signature of a kind jkit doesn't accept (type "
                        + sig.getSignatureType() + ", hash " + sig.getHashAlgorithm() + ")");
            }
            if (signer.hasRevocation() || primary.hasRevocation()) {
                return new Result(Outcome.UNKNOWN_KEY, null, whose + " is revoked");
            }
            if (data == null) {
                return null;
            }
            try {
                sig.init(new BcPGPContentVerifierBuilderProvider(), signer);
                try (InputStream in = data.open()) {
                    byte[] buffer = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        sig.update(buffer, 0, n);
                    }
                }
                if (sig.verify()) {
                    String person = key.fingerprints().size() > 1 ? person(primary) : null;
                    return new Result(Outcome.GOOD, key.owner(), key.owner() + " ("
                            + (person == null ? "" : person + ", ") + "key " + longId(fingerprint(primary)) + ")");
                }
                return new Result(Outcome.BAD, null, whose + " made it");
            } catch (PGPException e) {
                return new Result(Outcome.UNREADABLE, null, "it can't be checked (" + e.getMessage() + ")");
            }
        }
        return retired != null ? new Result(Outcome.RETIRED_KEY, null, retired)
                : new Result(Outcome.UNKNOWN_KEY, null, others);
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

    /**
     * The key rings for {@code key}, checked to hold exactly the primary keys jkit expects: a key
     * added to the resource by mistake, or one missing, stops jkit rather than widening trust.
     */
    static PGPPublicKeyRingCollection ring(Key key) {
        String resource = "keys/" + key.resource();
        try (InputStream in = Signatures.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new JkitException("jkit is missing its built-in key " + resource);
            }
            PGPPublicKeyRingCollection ring = new PGPPublicKeyRingCollection(PGPUtil.getDecoderStream(in),
                    new BcKeyFingerprintCalculator());
            Set<String> found = new HashSet<>();
            for (Iterator<PGPPublicKeyRing> it = ring.getKeyRings(); it.hasNext(); ) {
                found.add(fingerprint(it.next().getPublicKey()));
            }
            if (ring.size() != key.fingerprints().size() || !found.equals(Set.copyOf(key.fingerprints()))) {
                throw new JkitException("jkit's built-in key " + resource + " isn't " + String.join(", ", key.fingerprints()));
            }
            return ring;
        } catch (IOException | PGPException e) {
            throw new JkitException("jkit can't read its built-in key " + resource + ": " + e.getMessage());
        }
    }

    private static String fingerprint(PGPPublicKey key) {
        return HexFormat.of().withUpperCase().formatHex(key.getFingerprint());
    }

    /** The name in the key's first user ID, without the email: "Tamas Cservenak". Null if it has none fit to show. */
    private static String person(PGPPublicKey primary) {
        Iterator<String> ids = primary.getUserIDs();
        if (!ids.hasNext()) {
            return null;
        }
        String name = ids.next().replaceAll("\\s*<[^>]*>\\s*$", "").replaceAll("\\s*\\([^)]*\\)", "").strip();
        return name.matches("[\\p{L} .'-]{1,60}") ? name : null;
    }

    private static String keyId(long id) {
        return String.format(Locale.ROOT, "%016X", id);
    }

    /** The last 16 hex digits, as gpg shows a long key ID. */
    private static String longId(String fingerprint) {
        return fingerprint.substring(fingerprint.length() - 16);
    }
}
