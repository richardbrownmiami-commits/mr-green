package im.mrgreen.plugins

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Signature/key pin utilities. Import and pin must only be called after an explicit owner confirmation UI. */
class PackTrust(private val pinFile: File? = null, private val testKeyPair: java.security.KeyPair? = null) {
    fun fingerprint(key: PublicKey): String = sha256(key.encoded).chunked(2).joinToString(":").uppercase()

    fun pinOwnerKey(key: PublicKey) {
        val f = pinFile ?: error("Owner key storage unavailable")
        f.parentFile?.mkdirs()
        val bytes = key.encoded
        require(bytes.size <= MAX_KEY_BYTES)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(Base64.getEncoder().encodeToString(bytes))
        if (!tmp.renameTo(f)) { f.delete(); check(tmp.renameTo(f)) }
    }

    fun isPinned(key: PublicKey): Boolean {
        if (runCatching { localPublicKey()?.encoded?.let { MessageDigestHolder.equal(it, key.encoded) } == true }.getOrDefault(false)) return true
        val f = pinFile ?: return false
        if (!f.isFile || f.length() > MAX_KEY_BYTES * 2) return false
        return runCatching { MessageDigestHolder.equal(Base64.getDecoder().decode(f.readText()), key.encoded) }.getOrDefault(false)
    }

    fun localPublicKey(): PublicKey? = testKeyPair?.public ?: runCatching {
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(KEY_ALIAS)) null else ks.getCertificate(KEY_ALIAS).publicKey
    }.getOrNull()

    fun verify(canonicalManifest: ByteArray, signatureBase64: String, algorithm: String, key: PublicKey): Boolean = runCatching {
        require((algorithm == "SHA256withRSA" && key.algorithm.equals("RSA", true)) ||
            (algorithm == "SHA256withECDSA" && key.algorithm.equals("EC", true)))
        require(strongEnough(key))
        val signatureBytes = Base64.getDecoder().decode(signatureBase64)
        require(signatureBytes.size in 32..MAX_SIGNATURE_BYTES)
        Signature.getInstance(algorithm).run { initVerify(key); update(canonicalManifest); verify(signatureBytes) }
    }.getOrDefault(false)

    fun decodePublicKey(algorithm: String, base64: String): PublicKey {
        require(algorithm == "RSA" || algorithm == "EC")
        val encoded = Base64.getDecoder().decode(base64)
        require(encoded.size in 64..MAX_KEY_BYTES)
        val key = KeyFactory.getInstance(algorithm).generatePublic(X509EncodedKeySpec(encoded))
        require(strongEnough(key))
        return key
    }

    private fun strongEnough(key: PublicKey): Boolean = when (key.algorithm.uppercase()) {
        "RSA" -> (key as? java.security.interfaces.RSAKey)?.modulus?.bitLength()?.let { it >= 2048 } == true
        "EC" -> (key as? java.security.interfaces.ECKey)?.params?.curve?.field?.fieldSize?.let { it >= 256 } == true
        else -> false
    }

    fun sign(canonicalManifest: ByteArray): Pair<String, String> {
        testKeyPair?.let { pair ->
            val bytes = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(canonicalManifest) }.sign()
            return Base64.getEncoder().encodeToString(bytes) to Base64.getEncoder().encodeToString(pair.public.encoded)
        }
        val alias = KEY_ALIAS
        val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            val algo = "RSA"
            KeyPairGenerator.getInstance(algo, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setDigests(KeyProperties.DIGEST_SHA256).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).setKeySize(2048).build())
            }.generateKeyPair()
        }
        val privateKey = ks.getKey(alias, null) as java.security.PrivateKey
        val publicKey = ks.getCertificate(alias).publicKey
        val sig = Signature.getInstance("SHA256withRSA").apply { initSign(privateKey); update(canonicalManifest) }.sign()
        return Base64.getEncoder().encodeToString(sig) to Base64.getEncoder().encodeToString(publicKey.encoded)
    }

    companion object {
        const val KEY_ALIAS = "mrgreen.pack.local.v1"
        const val MAX_KEY_BYTES = 8192
        const val MAX_SIGNATURE_BYTES = 8192
        fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

private object MessageDigestHolder {
    fun equal(a: ByteArray, b: ByteArray): Boolean = java.security.MessageDigest.isEqual(a, b)
}
