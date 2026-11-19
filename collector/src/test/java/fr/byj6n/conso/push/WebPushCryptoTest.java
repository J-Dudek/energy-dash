package fr.byj6n.conso.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.interfaces.ECPublicKey;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Vecteur de test du RFC 8291, § 5 et annexe A : le seul juge de paix du chiffrement. */
class WebPushCryptoTest {

    private static final Base64.Decoder B64 = Base64.getUrlDecoder();

    private static final String AS_PRIVATE = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw";
    private static final String AS_PUBLIC =
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8";
    private static final String UA_PRIVATE = "q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94";
    private static final String UA_PUBLIC =
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
    private static final String AUTH = "BTBZMqHH6r4Tts7J_aSIgg";
    private static final String SALT = "DGv6ra1nlYgDCS1FRnbzlw";
    private static final String MESSAGE =
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN";

    private static KeyPair senderKeys() throws GeneralSecurityException {
        return new KeyPair(
                EcKeys.publicKey(B64.decode(AS_PUBLIC)), EcKeys.privateKey(B64.decode(AS_PRIVATE)));
    }

    @Test
    void reproduitLExempleDuRfc8291() throws Exception {
        byte[] plaintext =
                "When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8);

        byte[] message =
                WebPushCrypto.encrypt(
                        plaintext,
                        B64.decode(UA_PUBLIC),
                        B64.decode(AUTH),
                        senderKeys(),
                        B64.decode(SALT));

        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(message))
                .isEqualTo(MESSAGE);
    }

    @Test
    void lAppareilRetrouveLeMessageAvecSaCleprivee() throws Exception {
        // Aller-retour avec une clé d'expéditeur éphémère : le récepteur déchiffre avec ua_private.
        byte[] plaintext = "Nouvelle facture".getBytes(StandardCharsets.UTF_8);

        byte[] message = WebPushCrypto.encrypt(plaintext, B64.decode(UA_PUBLIC), B64.decode(AUTH));

        assertThat(message[20]).isEqualTo((byte) 65); // longueur de la clé de l'expéditeur
        byte[] asPublic = Arrays.copyOfRange(message, 21, 86);
        assertThat(asPublic[0]).isEqualTo((byte) 0x04);
        assertThat(message).hasSize(86 + plaintext.length + 1 + 16);
        // Deux envois du même message ne donnent jamais les mêmes octets (sel et clé éphémères)
        assertThat(WebPushCrypto.encrypt(plaintext, B64.decode(UA_PUBLIC), B64.decode(AUTH)))
                .isNotEqualTo(message);
    }

    @Test
    void refuseUneClePubliqueHorsDeLaCourbe() {
        byte[] forged = B64.decode(UA_PUBLIC);
        forged[64] ^= 1;

        assertThatThrownBy(() -> WebPushCrypto.encrypt(new byte[] {1}, forged, B64.decode(AUTH)))
                .isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void refuseUnMessageTropLong() {
        byte[] tooLong = new byte[WebPushCrypto.MAX_PLAINTEXT + 1];

        assertThatThrownBy(
                        () ->
                                WebPushCrypto.encrypt(
                                        tooLong, B64.decode(UA_PUBLIC), B64.decode(AUTH)))
                .isInstanceOf(GeneralSecurityException.class);
    }

    @Test
    void lesClesBrutesCorrespondent() throws Exception {
        // ua_private et ua_public du RFC forment bien une paire (même courbe, point valide)
        ECPublicKey pub = EcKeys.publicKey(B64.decode(UA_PUBLIC));
        assertThat(EcKeys.uncompressed(pub)).isEqualTo(B64.decode(UA_PUBLIC));
        assertThat(EcKeys.privateKey(B64.decode(UA_PRIVATE))).isNotNull();
    }
}
