package fr.byj6n.conso.push;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chiffrement d'un message Web Push : RFC 8291 (clés) avec le codage de contenu aes128gcm du RFC
 * 8188, en un seul enregistrement. Uniquement la bibliothèque standard du JDK : aucune dépendance
 * de plus à auditer.
 */
final class WebPushCrypto {

    /** Un push service n'est pas tenu d'accepter plus de 4096 octets (RFC 8291, § 4). */
    static final int MAX_PLAINTEXT = 3993;

    private static final int RECORD_SIZE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();

    private WebPushCrypto() {}

    static byte[] encrypt(byte[] plaintext, byte[] uaPublic, byte[] authSecret)
            throws GeneralSecurityException {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(plaintext, uaPublic, authSecret, gen.generateKeyPair(), salt);
    }

    /** Variante déterministe, pour le vecteur de test du RFC 8291. */
    static byte[] encrypt(
            byte[] plaintext, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt)
            throws GeneralSecurityException {
        if (plaintext.length > MAX_PLAINTEXT) {
            throw new GeneralSecurityException("Message trop long pour Web Push");
        }
        if (authSecret.length != 16 || salt.length != 16) {
            throw new GeneralSecurityException("Secret d'authentification ou sel invalide");
        }
        byte[] asPublic = EcKeys.uncompressed((ECPublicKey) asKeys.getPublic());

        KeyAgreement ecdh = KeyAgreement.getInstance("ECDH");
        ecdh.init(asKeys.getPrivate());
        ecdh.doPhase(EcKeys.publicKey(uaPublic), true);
        byte[] ecdhSecret = ecdh.generateSecret();

        byte[] prkKey = hmac(authSecret, ecdhSecret);
        byte[] keyInfo =
                concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = hmac(prkKey, concat(keyInfo, new byte[] {1}));

        byte[] prk = hmac(salt, ikm);
        byte[] cek =
                Arrays.copyOf(
                        hmac(
                                prk,
                                concat(
                                        "Content-Encoding: aes128gcm\0"
                                                .getBytes(StandardCharsets.US_ASCII),
                                        new byte[] {1})),
                        16);
        byte[] nonce =
                Arrays.copyOf(
                        hmac(
                                prk,
                                concat(
                                        "Content-Encoding: nonce\0"
                                                .getBytes(StandardCharsets.US_ASCII),
                                        new byte[] {1})),
                        12);

        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(cek, "AES"),
                new GCMParameterSpec(128, nonce));
        byte[] record =
                aes.doFinal(concat(plaintext, new byte[] {2})); // 0x02 : dernier enregistrement

        ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
        header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
        return concat(header.array(), record);
    }

    private static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] p : parts) length += p.length;
        byte[] out = new byte[length];
        int offset = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, offset, p.length);
            offset += p.length;
        }
        return out;
    }
}
