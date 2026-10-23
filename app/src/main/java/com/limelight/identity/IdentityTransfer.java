package com.limelight.identity;

import android.content.Context;
import android.util.Base64;

import com.limelight.nvstream.http.ComputerDetails;

import org.json.JSONObject;

import org.bouncycastle.jce.provider.BouncyCastleProvider;

import java.io.File;
import java.io.FileInputStream;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Moves a Sunshine client identity (certificate and private key) from one MoonCast device to another, encrypted to the
 * receiver's public key so the LAN only carries ciphertext. The receiver decrypts it into memory only.
 *
 * Wire format of an encrypted blob: ephemeral X25519 public key (32) | GCM nonce (12) | AES-256-GCM ciphertext.
 * The key is HKDF-SHA256 over the X25519 shared secret, salted with both public keys.
 */
public final class IdentityTransfer {
    private static final Provider BC = new BouncyCastleProvider();
    private static final String INFO = "mooncast-identity-v1";
    private static final int RAW_PUB = 32;
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;
    // ASN.1 prefix of an X25519 SubjectPublicKeyInfo; the raw 32-byte key follows it.
    private static final byte[] SPKI_PREFIX = {
            0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x6e, 0x03, 0x21, 0x00
    };

    private IdentityTransfer() {}

    /** A new key pair for a receiver. The private half stays on the receiver; only the raw public key is shared. */
    public static KeyPair newReceiverKeys() throws GeneralSecurityException {
        return KeyPairGenerator.getInstance("X25519", BC).generateKeyPair();
    }

    /** The 32-byte raw X25519 public key, as published to senders. */
    public static byte[] rawPublic(PublicKey pub) {
        byte[] encoded = pub.getEncoded();
        return Arrays.copyOfRange(encoded, encoded.length - RAW_PUB, encoded.length);
    }

    /** Encrypts [plaintext] so only the holder of the receiver's private key can read it. */
    public static byte[] encrypt(byte[] receiverRawPub, byte[] plaintext) throws GeneralSecurityException {
        KeyPair eph = newReceiverKeys();
        byte[] ephRaw = rawPublic(eph.getPublic());
        byte[] key = deriveKey(sharedSecret(eph.getPrivate(), receiverRawPub), ephRaw, receiverRawPub);

        byte[] nonce = new byte[NONCE];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(INFO.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] ct = cipher.doFinal(plaintext);

        return ByteBuffer.allocate(RAW_PUB + NONCE + ct.length).put(ephRaw).put(nonce).put(ct).array();
    }

    /** Decrypts a blob made by [encrypt] for this receiver. Throws if it was tampered with or meant for someone else. */
    public static byte[] decrypt(PrivateKey receiverPriv, byte[] receiverRawPub, byte[] blob) throws GeneralSecurityException {
        if (blob.length < RAW_PUB + NONCE + TAG_BITS / 8) throw new GeneralSecurityException("short blob");
        byte[] ephRaw = Arrays.copyOfRange(blob, 0, RAW_PUB);
        byte[] nonce = Arrays.copyOfRange(blob, RAW_PUB, RAW_PUB + NONCE);
        byte[] ct = Arrays.copyOfRange(blob, RAW_PUB + NONCE, blob.length);
        byte[] key = deriveKey(sharedSecret(receiverPriv, ephRaw), ephRaw, receiverRawPub);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(INFO.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return cipher.doFinal(ct);
    }

    /**
     * This device's Sunshine client identity and one PC's details, sealed for a receiver. [receiverKeyB64] is the
     * receiver's published key. Returns base64 of the encrypted blob, or null if this device has no identity or the PC
     * has no certificate yet (it has never paired).
     */
    public static String sealIdentity(Context ctx, String receiverKeyB64, ComputerDetails pc) {
        try {
            byte[] cert = readFile(new File(ctx.getFilesDir(), "client.crt"));
            byte[] key = readFile(new File(ctx.getFilesDir(), "client.key"));
            if (cert == null || key == null || pc == null || pc.serverCert == null) return null;
            ComputerDetails.AddressTuple addr = pc.localAddress != null ? pc.localAddress : pc.activeAddress;
            if (addr == null) return null;
            JSONObject j = new JSONObject()
                    .put("cert", Base64.encodeToString(cert, Base64.NO_WRAP))
                    .put("key", Base64.encodeToString(key, Base64.NO_WRAP))
                    .put("uuid", pc.uuid).put("name", pc.name)
                    .put("address", addr.address).put("port", addr.port)
                    .put("serverCert", Base64.encodeToString(pc.serverCert.getEncoded(), Base64.NO_WRAP));
            byte[] recvRaw = Base64.decode(receiverKeyB64, Base64.DEFAULT);
            return Base64.encodeToString(encrypt(recvRaw, j.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** Opens a blob from [sealIdentity] on the receiver. Returns the JSON: cert, key, uuid, name, address, port, serverCert. */
    public static JSONObject openIdentity(PrivateKey receiverPriv, byte[] receiverRawPub, String blobB64) throws Exception {
        byte[] plain = decrypt(receiverPriv, receiverRawPub, Base64.decode(blobB64, Base64.DEFAULT));
        return new JSONObject(new String(plain, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static byte[] readFile(File f) throws java.io.IOException {
        if (!f.isFile()) return null;
        byte[] out = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < out.length) {
                int n = in.read(out, off, out.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return out;
    }

    /** Packs a certificate and its private key into one plaintext: length-prefixed, DER certificate then PKCS#8 key. */
    public static byte[] pack(byte[] certDer, byte[] keyPkcs8) {
        return ByteBuffer.allocate(8 + certDer.length + keyPkcs8.length)
                .putInt(certDer.length).put(certDer).putInt(keyPkcs8.length).put(keyPkcs8).array();
    }

    /** Reverses [pack]. Returns {certificate DER, key PKCS#8}. */
    public static byte[][] unpack(byte[] packed) throws GeneralSecurityException {
        ByteBuffer b = ByteBuffer.wrap(packed);
        if (b.remaining() < 4) throw new GeneralSecurityException("short identity");
        byte[] cert = new byte[b.getInt()];
        if (b.remaining() < cert.length + 4) throw new GeneralSecurityException("short identity");
        b.get(cert);
        byte[] key = new byte[b.getInt()];
        if (b.remaining() != key.length) throw new GeneralSecurityException("bad identity length");
        b.get(key);
        return new byte[][] { cert, key };
    }

    private static byte[] sharedSecret(PrivateKey priv, byte[] peerRawPub) throws GeneralSecurityException {
        KeyAgreement ka = KeyAgreement.getInstance("X25519", BC);
        ka.init(priv);
        ka.doPhase(publicFromRaw(peerRawPub), true);
        return ka.generateSecret();
    }

    private static PublicKey publicFromRaw(byte[] raw) throws GeneralSecurityException {
        ByteArrayOutputStream spki = new ByteArrayOutputStream();
        spki.write(SPKI_PREFIX, 0, SPKI_PREFIX.length);
        spki.write(raw, 0, raw.length);
        return java.security.KeyFactory.getInstance("X25519", BC)
                .generatePublic(new java.security.spec.X509EncodedKeySpec(spki.toByteArray()));
    }

    /** HKDF-SHA256 (RFC 5869), salted with both public keys so the key is bound to this exchange. */
    private static byte[] deriveKey(byte[] shared, byte[] ephRaw, byte[] recvRaw) throws GeneralSecurityException {
        byte[] salt = ByteBuffer.allocate(RAW_PUB * 2).put(ephRaw).put(recvRaw).array();
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(salt, "HmacSHA256"));
        byte[] prk = mac.doFinal(shared);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(INFO.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), 32);
    }
}
