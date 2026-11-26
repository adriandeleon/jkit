package dev.jkit.core;

import dev.jkit.model.JkitException;
import dev.jkit.model.Tool;
import dev.jkit.model.Vendor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.SignatureSubpacketTags;
import org.bouncycastle.bcpg.sig.KeyFlags;
import org.bouncycastle.openpgp.PGPCompressedData;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.bouncycastle.openpgp.PGPPublicKeyRing;
import org.bouncycastle.openpgp.PGPPublicKeyRingCollection;
import org.bouncycastle.openpgp.PGPSignature;
import org.bouncycastle.openpgp.PGPSignatureList;
import org.bouncycastle.openpgp.PGPSignatureSubpacketVector;
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
     * The signing keys of one publisher: the {@code .asc} resource under {@code dev/jkit/core/keys/},
     * and the fingerprints of the primary keys it holds. The resource must hold exactly those keys,
     * which is checked when it is loaded.
     *
     * @param vendor the foojay name of the JDK vendor whose builds these keys sign, or null
     * @param tool the tool whose downloads these keys sign, when {@code vendor} is null
     */
    record Key(String vendor, Tool tool, String owner, String resource, List<String> fingerprints) {
        Key {
            // A key with neither a vendor nor a tool is jkit's own release key (JKIT), which signs
            // jkit's releases rather than downloads.
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
        /** Not made by a key jkit has for this publisher, which may have a new key. Only the checksum counts. */
        UNKNOWN_KEY,
        /** Made by one of the publisher's {@link #RETIRED} keys, which jkit doesn't have. Only the checksum counts. */
        RETIRED_KEY,
        /**
         * Not a signature jkit can check (damaged, a weak hash, or made with a weak key or one not
         * for signing). Only the checksum counts.
         */
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

    /** The signed data, opened again for each signature tried. */
    @FunctionalInterface
    interface Data {
        InputStream open() throws IOException;
    }

    /**
     * Checks what can be told from {@code signature} alone, without the file it covers. Returns null
     * when a key jkit has for {@code tool} or {@code vendor} made it and it is a kind jkit accepts,
     * so only checking the file can decide; otherwise returns why it can't be checked (never GOOD
     * or BAD). An install calls this before it downloads, so that a signature it would refuse
     * anyway doesn't cost the whole download.
     */
    public static Result precheck(byte[] signature, Tool tool, Vendor vendor) throws IOException {
        Key key = key(tool, vendor);
        if (key == null) {
            throw new IllegalArgumentException("no key for " + tool + " " + vendor);
        }
        return verify((Data) null, signature, key, ring(key));
    }

    /** With {@code data} null, stops before the file would be read and returns null ({@link #precheck}). */
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
            // The signing key may be a subkey (sbt, Maven), so name the primary key it belongs to.
            PGPPublicKey primary = ring.getPublicKeyRing(sig.getKeyID()).getPublicKey();
            String whose = key.owner() + "'s key " + longId(fingerprint(primary));
            if (sig.getSignatureType() != PGPSignature.BINARY_DOCUMENT || !STRONG_HASHES.contains(sig.getHashAlgorithm())) {
                return new Result(Outcome.UNREADABLE, null, "a signature of a kind jkit doesn't accept (type "
                        + sig.getSignatureType() + ", hash " + sig.getHashAlgorithm() + ")");
            }
            if (signer.hasRevocation() || primary.hasRevocation()) {
                return new Result(Outcome.UNKNOWN_KEY, null, whose + " is revoked");
            }
            String unfit = unfit(signer, primary, whose);
            if (unfit != null) {
                return new Result(Outcome.UNREADABLE, null, unfit);
            }
            // An expired key may have been extended since, which a newer jkit knows: an unknown key.
            String expired = expired(signer, primary, sig, whose);
            if (expired != null) {
                return new Result(Outcome.UNKNOWN_KEY, null, expired);
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

    /**
     * Why {@code signer} may not sign, or null when it may: its self-signature ({@link #selfSigned};
     * for a subkey, its newest binding signature) must mark it for signing, and it must be RSA of at
     * least 2048 bits, EdDSA, or ECDSA of at least 256 bits. The rings jkit has hold encryption
     * subkeys too, and Guillaume Nodet's DSA-1024 primary key, which signed his Maven releases
     * before 3.9.0: a signature by one of those doesn't count.
     */
    static String unfit(PGPPublicKey signer, PGPPublicKey primary, String whose) {
        String which = signer.getKeyID() == primary.getKeyID() ? whose : whose + "'s subkey " + longId(fingerprint(signer));
        PGPSignatureSubpacketVector hashed = selfSigned(signer, primary);
        if (hashed == null || !hashed.hasSubpacket(SignatureSubpacketTags.KEY_FLAGS)
                || (hashed.getKeyFlags() & KeyFlags.SIGN_DATA) == 0) {
            return "it is made with " + which + ", which isn't a signing key";
        }
        int bits = signer.getBitStrength();
        return strong(signer.getAlgorithm(), bits) ? null : "it is made with " + which + ", a "
                + algorithm(signer.getAlgorithm()) + "-" + bits + " key, which jkit doesn't accept";
    }

    // RSA_SIGN, RSA_ENCRYPT and ELGAMAL_GENERAL are deprecated because RFC 9580 deprecates the
    // algorithms; old keys still carry them, and there is no other name for the tags.
    @SuppressWarnings("deprecation")
    private static boolean strong(int algorithm, int bits) {
        return switch (algorithm) {
            case PublicKeyAlgorithmTags.RSA_GENERAL, PublicKeyAlgorithmTags.RSA_SIGN -> bits >= 2048;
            case PublicKeyAlgorithmTags.EDDSA_LEGACY, PublicKeyAlgorithmTags.Ed25519, PublicKeyAlgorithmTags.Ed448 -> true;
            case PublicKeyAlgorithmTags.ECDSA -> bits >= 256;
            default -> false;
        };
    }

    /** Why {@code sig} was made after {@code signer}, or the primary key it belongs to, expired; null if it wasn't. */
    private static String expired(PGPPublicKey signer, PGPPublicKey primary, PGPSignature sig, String whose) {
        Instant made = sig.getCreationTime().toInstant();
        for (PGPPublicKey k : signer.getKeyID() == primary.getKeyID() ? List.of(signer) : List.of(signer, primary)) {
            PGPSignatureSubpacketVector hashed = selfSigned(k, primary);
            long seconds = hashed == null ? 0 : hashed.getKeyExpirationTime();
            Instant end = k.getCreationTime().toInstant().plusSeconds(seconds);
            if (seconds > 0 && made.isAfter(end)) {
                String which = k.getKeyID() == primary.getKeyID() ? whose : whose + "'s subkey " + longId(fingerprint(k));
                return "it was made on " + LocalDate.ofInstant(made, ZoneOffset.UTC) + ", after " + which
                        + " expired on " + LocalDate.ofInstant(end, ZoneOffset.UTC);
            }
        }
        return null;
    }

    /**
     * The hashed subpackets of the self-signature that says what {@code key} is for and when it
     * expires, as RFC 9580 (5.2.3.10) picks it. For a subkey, the primary key's newest binding
     * signature. For a primary key, its newest direct-key signature when that carries key flags;
     * otherwise the newest self-signature of its primary user ID. A later signature that says
     * nothing about the key's use must not hide the one that does: gpg's addrevoker adds a
     * direct-key signature with no key flags, and a user ID added later may be self-signed without
     * them. So among the user IDs, one whose newest self-signature has key flags comes first, then
     * one marked primary, then the newest. Null if the key has no such signature.
     */
    private static PGPSignatureSubpacketVector selfSigned(PGPPublicKey key, PGPPublicKey primary) {
        long by = primary.getKeyID();
        if (key.getKeyID() != by) {
            PGPSignature binding = newest(key.getSignaturesOfType(PGPSignature.SUBKEY_BINDING), by);
            return binding == null ? null : binding.getHashedSubPackets();
        }
        PGPSignature direct = null;
        for (Iterator<PGPSignature> it = key.getSignaturesOfType(PGPSignature.DIRECT_KEY); it.hasNext(); ) {
            PGPSignature s = it.next();
            if (s.getKeyID() == by && hasFlags(s) && (direct == null || s.getCreationTime().after(direct.getCreationTime()))) {
                direct = s;
            }
        }
        if (direct != null) {
            return direct.getHashedSubPackets();
        }
        PGPSignature best = null;
        for (Iterator<byte[]> ids = key.getRawUserIDs(); ids.hasNext(); ) {
            byte[] id = ids.next();
            PGPSignature cert = null;
            PGPSignature revoked = null;
            for (Iterator<PGPSignature> it = key.getSignaturesForID(id); it != null && it.hasNext(); ) {
                PGPSignature s = it.next();
                if (s.getKeyID() != by) {
                    continue;
                }
                int type = s.getSignatureType();
                if (type == PGPSignature.CERTIFICATION_REVOCATION) {
                    revoked = later(revoked, s);
                } else if (type == PGPSignature.DEFAULT_CERTIFICATION || type == PGPSignature.NO_CERTIFICATION
                        || type == PGPSignature.CASUAL_CERTIFICATION || type == PGPSignature.POSITIVE_CERTIFICATION) {
                    cert = later(cert, s);
                }
            }
            if (cert == null || (revoked != null && !cert.getCreationTime().after(revoked.getCreationTime()))) {
                continue; // a user ID revoked since its last self-signature says nothing about the key
            }
            if (best == null || rank(cert) > rank(best)
                    || (rank(cert) == rank(best) && cert.getCreationTime().after(best.getCreationTime()))) {
                best = cert;
            }
        }
        return best == null ? null : best.getHashedSubPackets();
    }

    private static boolean hasFlags(PGPSignature s) {
        return s.getHashedSubPackets() != null && s.getHashedSubPackets().hasSubpacket(SignatureSubpacketTags.KEY_FLAGS);
    }

    /** A user ID self-signature with key flags first, then one marking its user ID primary. */
    private static int rank(PGPSignature s) {
        boolean primaryId = s.getHashedSubPackets() != null && s.getHashedSubPackets().isPrimaryUserID();
        return (hasFlags(s) ? 2 : 0) + (primaryId ? 1 : 0);
    }

    private static PGPSignature later(PGPSignature a, PGPSignature b) {
        return a == null || b.getCreationTime().after(a.getCreationTime()) ? b : a;
    }

    private static PGPSignature newest(Iterator<PGPSignature> signatures, long by) {
        PGPSignature newest = null;
        while (signatures.hasNext()) {
            PGPSignature s = signatures.next();
            if (s.getKeyID() == by) {
                newest = later(newest, s);
            }
        }
        return newest;
    }

    @SuppressWarnings("deprecation") // the deprecated tags, as for strong()
    private static String algorithm(int tag) {
        return switch (tag) {
            case PublicKeyAlgorithmTags.RSA_GENERAL, PublicKeyAlgorithmTags.RSA_SIGN, PublicKeyAlgorithmTags.RSA_ENCRYPT -> "RSA";
            case PublicKeyAlgorithmTags.DSA -> "DSA";
            case PublicKeyAlgorithmTags.ELGAMAL_ENCRYPT, PublicKeyAlgorithmTags.ELGAMAL_GENERAL -> "ElGamal";
            case PublicKeyAlgorithmTags.ECDH -> "ECDH";
            case PublicKeyAlgorithmTags.X25519 -> "X25519";
            default -> "type " + tag;
        };
    }

    private static PGPSignatureList signatures(byte[] signature) throws IOException {
        // .sig files come binary (Temurin) or ASCII-armored; the decoder stream reads both.
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
