package fr.byj6n.conso.push;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;

/** Clés P-256 (courbe de Web Push et de VAPID) vers et depuis leur forme brute. */
final class EcKeys {

    static final int POINT_LENGTH = 65; // 0x04 || X (32) || Y (32)

    private static final ECParameterSpec P256 = p256();

    private EcKeys() {}

    private static ECParameterSpec p256() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Courbe P-256 indisponible", e);
        }
    }

    /**
     * Clé publique depuis le point non compressé. Le point doit être sur la courbe : sinon une clé
     * forgée pourrait faire fuiter des bits du secret (RFC 8291, § 7).
     */
    static ECPublicKey publicKey(byte[] point) throws GeneralSecurityException {
        if (point.length != POINT_LENGTH || point[0] != 0x04) {
            throw new GeneralSecurityException("Clé publique P-256 non compressée attendue");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(point, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(point, 33, 65));
        BigInteger p = ((ECFieldFp) P256.getCurve().getField()).getP();
        BigInteger left = y.multiply(y).mod(p);
        BigInteger right =
                x.pow(3).add(P256.getCurve().getA().multiply(x)).add(P256.getCurve().getB()).mod(p);
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0 || !left.equals(right)) {
            throw new GeneralSecurityException("Point hors de la courbe P-256");
        }
        return (ECPublicKey)
                KeyFactory.getInstance("EC")
                        .generatePublic(new ECPublicKeySpec(new ECPoint(x, y), P256));
    }

    /** Clé privée depuis son scalaire brut de 32 octets. */
    static ECPrivateKey privateKey(byte[] scalar) throws GeneralSecurityException {
        if (scalar.length != 32) {
            throw new GeneralSecurityException("Clé privée P-256 de 32 octets attendue");
        }
        BigInteger s = new BigInteger(1, scalar);
        if (s.signum() == 0 || s.compareTo(P256.getOrder()) >= 0) {
            throw new GeneralSecurityException("Clé privée P-256 hors limites");
        }
        return (ECPrivateKey)
                KeyFactory.getInstance("EC").generatePrivate(new ECPrivateKeySpec(s, P256));
    }

    static byte[] uncompressed(ECPublicKey key) {
        byte[] out = new byte[POINT_LENGTH];
        out[0] = 0x04;
        System.arraycopy(fixed(key.getW().getAffineX()), 0, out, 1, 32);
        System.arraycopy(fixed(key.getW().getAffineY()), 0, out, 33, 32);
        return out;
    }

    private static byte[] fixed(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[32];
        int copy = Math.min(raw.length, 32);
        System.arraycopy(raw, raw.length - copy, out, 32 - copy, copy);
        return out;
    }
}
