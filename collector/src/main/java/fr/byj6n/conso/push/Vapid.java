package fr.byj6n.conso.push;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.ObjectMapper;

/**
 * Identification du serveur d'application auprès des push services (VAPID, RFC 8292) : un jeton JWT
 * signé ES256, valable moins de 24 h, qui dit « je suis le détenteur de cette clé publique ».
 */
public final class Vapid {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Duration VALIDITY = Duration.ofHours(12);

    private final ECPrivateKey privateKey;
    private final String publicKey;
    private final String subject;
    private final ObjectMapper mapper;

    private Vapid(ECPrivateKey privateKey, String publicKey, String subject, ObjectMapper mapper) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.subject = subject;
        this.mapper = mapper;
    }

    /**
     * Construit l'identité VAPID du serveur et vérifie que ses deux clés vont ensemble.
     *
     * @param publicKey point P-256 non compressé, en base64url (65 octets)
     * @param privateKey scalaire P-256, en base64url (32 octets)
     * @param subject {@code mailto:} ou {@code https:} : Apple refuse tout autre format
     * @throws IllegalArgumentException si une valeur est invalide ou si les deux clés ne vont pas
     *     ensemble
     */
    public static Vapid of(
            String publicKey, String privateKey, String subject, ObjectMapper mapper) {
        if (subject == null
                || !(subject.startsWith("mailto:") || subject.startsWith("https://"))
                || subject.matches(".*[\"\\\\\\s].*")) {
            throw new IllegalArgumentException(
                    "CONSO_PUSH_SUBJECT doit être mailto:adresse ou https://site (sans espace)");
        }
        try {
            byte[] pub = Base64.getUrlDecoder().decode(publicKey.trim());
            ECPublicKey ecPublic = EcKeys.publicKey(pub);
            ECPrivateKey ecPrivate =
                    EcKeys.privateKey(Base64.getUrlDecoder().decode(privateKey.trim()));
            Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
            signer.initSign(ecPrivate);
            signer.update(pub);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(ecPublic);
            verifier.update(pub);
            if (!verifier.verify(signature)) {
                throw new IllegalArgumentException(
                        "Les clés VAPID publique et privée ne correspondent pas");
            }
            return new Vapid(ecPrivate, B64.encodeToString(pub), subject, mapper);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            if (e instanceof IllegalArgumentException iae && iae.getMessage() != null) throw iae;
            throw new IllegalArgumentException("Clés VAPID invalides (base64url P-256 attendu)", e);
        }
    }

    public String publicKey() {
        return publicKey;
    }

    /** En-tête {@code Authorization} pour un push service ; {@code audience} = son origine. */
    String authorization(String audience, Instant now) throws GeneralSecurityException {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("aud", audience);
        claims.put("exp", now.plus(VALIDITY).getEpochSecond());
        claims.put("sub", subject);
        String unsigned =
                B64.encodeToString(
                                "{\"typ\":\"JWT\",\"alg\":\"ES256\"}"
                                        .getBytes(StandardCharsets.UTF_8))
                        + "."
                        + B64.encodeToString(
                                mapper.writeValueAsString(claims).getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(privateKey);
        signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
        return "vapid t=" + unsigned + "." + B64.encodeToString(signer.sign()) + ", k=" + publicKey;
    }
}
