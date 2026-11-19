package fr.byj6n.conso.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class VapidTest {

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private final ObjectMapper mapper = new ObjectMapper();

    private static String[] newKeys() throws Exception {
        var gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = gen.generateKeyPair();
        byte[] scalar = ((ECPrivateKey) pair.getPrivate()).getS().toByteArray();
        byte[] fixed = new byte[32];
        int n = Math.min(32, scalar.length);
        System.arraycopy(scalar, scalar.length - n, fixed, 32 - n, n);
        return new String[] {
            B64.encodeToString(EcKeys.uncompressed((ECPublicKey) pair.getPublic())),
            B64.encodeToString(fixed)
        };
    }

    @Test
    void signeUnJwtQueLaClePubliqueVerifie() throws Exception {
        String[] keys = newKeys();
        Vapid vapid = Vapid.of(keys[0], keys[1], "mailto:moi@example.org", mapper);

        String header =
                vapid.authorization(
                        "https://fcm.googleapis.com", Instant.parse("2026-11-19T10:00:00Z"));

        assertThat(header).startsWith("vapid t=").endsWith(", k=" + keys[0]);
        String jwt = header.substring("vapid t=".length(), header.indexOf(", k="));
        String[] parts = jwt.split("\\.", -1);
        assertThat(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8))
                .isEqualTo("{\"typ\":\"JWT\",\"alg\":\"ES256\"}");
        var claims = mapper.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(claims.path("aud").asText()).isEqualTo("https://fcm.googleapis.com");
        assertThat(claims.path("sub").asText()).isEqualTo("mailto:moi@example.org");
        assertThat(claims.path("exp").asLong())
                .isEqualTo(Instant.parse("2026-11-19T22:00:00Z").getEpochSecond());
        Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
        verifier.initVerify(EcKeys.publicKey(Base64.getUrlDecoder().decode(keys[0])));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    @Test
    void refuseDesClesQuiNeVontPasEnsemble() throws Exception {
        String[] a = newKeys();
        String[] b = newKeys();

        assertThatThrownBy(() -> Vapid.of(a[0], b[1], "mailto:moi@example.org", mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ne correspondent pas");
    }

    @Test
    void refuseUnSujetQuApplePourraitRejeter() throws Exception {
        String[] keys = newKeys();

        for (String subject :
                new String[] {"", "moi@example.org", "http://example.org", "mailto:a b@c.d"}) {
            assertThatThrownBy(() -> Vapid.of(keys[0], keys[1], subject, mapper))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void refuseDesClesMalFormees() {
        assertThatThrownBy(() -> Vapid.of("pas-une-cle", "x", "mailto:moi@example.org", mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
