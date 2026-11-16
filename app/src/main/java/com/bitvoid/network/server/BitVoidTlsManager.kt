package com.bitvoid.network.server

import android.content.Context
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.TrustManagerFactory
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Creates an app-private local certificate authority and a short-lived gateway certificate.
 *
 * Browsers require a secure context before exposing camera and microphone APIs. BitVoid keeps
 * traffic local by serving the same portal over HTTPS on port 8443. A peer installs the exported
 * BitVoid Local CA once, then opens the secure URL shown by the gateway.
 */
object BitVoidTlsManager {
    const val SECURE_PORT = 8443

    data class ServerMaterial(
        val socketFactory: SSLServerSocketFactory,
        val caCertificateDer: ByteArray,
        val certificateHosts: Set<String>
    )

    private data class CertificateAuthority(
        val privateKey: PrivateKey,
        val certificate: X509Certificate
    )

    private val random = SecureRandom()
    private val provider = BouncyCastleProvider()
    private val storePassword = "BitVoid-Local-CA-2026".toCharArray()

    @Volatile
    private var cachedAuthority: CertificateAuthority? = null

    @Synchronized
    fun createServerMaterial(context: Context, rawIpv4Hosts: Collection<String>): ServerMaterial {
        val authority = loadOrCreateAuthority(context.applicationContext)
        val hosts = buildSet {
            add("127.0.0.1")
            add("192.168.43.1")
            add("192.168.49.1")
            rawIpv4Hosts.forEach { raw ->
                normalizeIpv4(raw)?.let(::add)
            }
        }
        val leafPair = generateRsaKeyPair()
        val leafCertificate = createGatewayCertificate(authority, leafPair, hosts)

        val serverStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(
                "bitvoid-gateway",
                leafPair.private,
                storePassword,
                arrayOf(leafCertificate, authority.certificate)
            )
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(serverStore, storePassword)
        }
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("bitvoid-local-ca", authority.certificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(keyManagers.keyManagers, trustManagers.trustManagers, random)
        }
        return ServerMaterial(
            socketFactory = sslContext.serverSocketFactory,
            caCertificateDer = authority.certificate.encoded,
            certificateHosts = hosts
        )
    }

    @Synchronized
    fun caCertificateDer(context: Context): ByteArray {
        return loadOrCreateAuthority(context.applicationContext).certificate.encoded
    }

    /** PEM is the most consistently importable form across Android, Windows, macOS, and iOS. */
    @Synchronized
    fun caCertificatePem(context: Context): ByteArray {
        val der = loadOrCreateAuthority(context.applicationContext).certificate.encoded
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray(Charsets.US_ASCII))
            .encodeToString(der)
        return ("-----BEGIN CERTIFICATE-----\n" + encoded +
            "\n-----END CERTIFICATE-----\n").toByteArray(Charsets.US_ASCII)
    }

    @Synchronized
    fun caCertificateSha256Fingerprint(context: Context): String {
        val der = loadOrCreateAuthority(context.applicationContext).certificate.encoded
        return MessageDigest.getInstance("SHA-256")
            .digest(der)
            .joinToString(":") { byte -> "%02X".format(byte.toInt() and 0xFF) }
    }

    private fun loadOrCreateAuthority(context: Context): CertificateAuthority {
        cachedAuthority?.let { return it }
        val storeFile = File(context.noBackupFilesDir, "bitvoid-local-ca.p12")
        val loaded = runCatching {
            if (!storeFile.isFile) return@runCatching null
            val keyStore = KeyStore.getInstance("PKCS12")
            storeFile.inputStream().use { keyStore.load(it, storePassword) }
            val key = keyStore.getKey("bitvoid-local-ca", storePassword) as? PrivateKey
                ?: return@runCatching null
            val cert = keyStore.getCertificate("bitvoid-local-ca") as? X509Certificate
                ?: return@runCatching null
            cert.checkValidity()
            CertificateAuthority(key, cert)
        }.getOrNull()
        if (loaded != null) {
            cachedAuthority = loaded
            return loaded
        }

        val pair = generateRsaKeyPair()
        val certificate = createRootCertificate(pair)
        val created = CertificateAuthority(pair.private, certificate)
        storeFile.parentFile?.mkdirs()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("bitvoid-local-ca", pair.private, storePassword, arrayOf(certificate))
        }
        storeFile.outputStream().use { keyStore.store(it, storePassword) }
        cachedAuthority = created
        return created
    }

    private fun createRootCertificate(pair: KeyPair): X509Certificate {
        val subject = X500Name("CN=BitVoid Local Root CA,O=BitVoid Local Mesh")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            subject,
            positiveSerial(),
            Date(now - FIVE_MINUTES_MS),
            Date(now + TEN_YEARS_MS),
            subject,
            pair.public
        )
        val extensions = JcaX509ExtensionUtils()
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature)
        )
        builder.addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(pair.public))
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(provider)
            .build(pair.private)
        return JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(builder.build(signer))
            .also {
                it.verify(pair.public)
                it.checkValidity()
            }
    }

    private fun createGatewayCertificate(
        authority: CertificateAuthority,
        pair: KeyPair,
        ipv4Hosts: Set<String>
    ): X509Certificate {
        val issuer = X500Name(authority.certificate.subjectX500Principal.name)
        val subject = X500Name("CN=BitVoid Local Gateway,O=BitVoid Local Mesh")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            positiveSerial(),
            Date(now - FIVE_MINUTES_MS),
            Date(now + TWO_YEARS_MS),
            subject,
            pair.public
        )
        val extensions = JcaX509ExtensionUtils()
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )
        builder.addExtension(
            Extension.extendedKeyUsage,
            false,
            ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth)
        )
        builder.addExtension(
            Extension.subjectAlternativeName,
            false,
            GeneralNames(
                buildList {
                    add(GeneralName(GeneralName.dNSName, "localhost"))
                    add(GeneralName(GeneralName.dNSName, "bitvoid.local"))
                    ipv4Hosts.forEach { add(GeneralName(GeneralName.iPAddress, it)) }
                }.toTypedArray()
            )
        )
        builder.addExtension(Extension.subjectKeyIdentifier, false, extensions.createSubjectKeyIdentifier(pair.public))
        builder.addExtension(
            Extension.authorityKeyIdentifier,
            false,
            extensions.createAuthorityKeyIdentifier(authority.certificate)
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider(provider)
            .build(authority.privateKey)
        return JcaX509CertificateConverter()
            .setProvider(provider)
            .getCertificate(builder.build(signer))
            .also {
                it.verify(authority.certificate.publicKey)
                it.checkValidity()
            }
    }

    private fun generateRsaKeyPair(): KeyPair {
        return KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
    }

    private fun positiveSerial(): BigInteger = BigInteger(160, random).abs().add(BigInteger.ONE)

    private fun normalizeIpv4(raw: String): String? {
        val clean = raw.trim()
        return runCatching {
            val address = InetAddress.getByName(clean)
            if (address is Inet4Address && address.hostAddress == clean) clean else null
        }.getOrNull()
    }

    private const val FIVE_MINUTES_MS = 5L * 60L * 1000L
    private const val TWO_YEARS_MS = 2L * 365L * 24L * 60L * 60L * 1000L
    private const val TEN_YEARS_MS = 10L * 365L * 24L * 60L * 60L * 1000L
}
