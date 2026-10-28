package com.superessence.app.apk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.widget.Toast
import com.superessence.app.core.Progress
import com.superessence.app.core.SavedFile
import com.superessence.app.core.Storage
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.ZipFile
import javax.security.auth.x500.X500Principal

/** Un paquet analysé : APK simple ou ensemble XAPK / APKS / APKM / ZIP. */
data class AppPackage(
    val file: File,
    val sourceName: String,
    val apkEntries: List<String>,
    val obbEntries: List<String>,
    val packageName: String?,
    val label: String?,
    val isPlainApk: Boolean,
) {
    val outputBase: String
        get() = Storage.sanitize(label ?: packageName ?: Storage.baseName(sourceName))
}

object ApkTools {

    fun analyze(ctx: Context, uri: Uri, p: Progress): AppPackage {
        val name = Storage.displayName(ctx, uri)
        val total = Storage.size(ctx, uri).coerceAtLeast(1)
        val dir = Storage.workDir(ctx, "apk")
        val file = Storage.copyToFile(ctx, uri, File(dir, "source.bin")) { b ->
            p.update(b.toFloat() / total, "Lecture de $name…")
        }
        if (Storage.extension(name) == "apk") {
            return AppPackage(file, name, emptyList(), emptyList(), null, Storage.baseName(name), true)
        }
        ZipFile(file).use { zip ->
            val entries = zip.entries().toList().filter { !it.isDirectory }
            val apks = entries.map { it.name }.filter { it.lowercase().endsWith(".apk") }
            val obbs = entries.map { it.name }.filter { it.lowercase().endsWith(".obb") }
            var pkg: String? = null
            var label: String? = null
            zip.getEntry("manifest.json")?.let { e ->
                runCatching {
                    val json = JSONObject(zip.getInputStream(e).readBytes().toString(Charsets.UTF_8))
                    pkg = json.optString("package_name").ifBlank { null }
                    label = json.optString("name").ifBlank { null }
                }
            }
            zip.getEntry("info.json")?.let { e ->
                runCatching {
                    val json = JSONObject(zip.getInputStream(e).readBytes().toString(Charsets.UTF_8))
                    pkg = pkg ?: json.optString("pname").ifBlank { null }
                    label = label ?: json.optString("app_name").ifBlank { null }
                }
            }
            if (apks.isEmpty()) error("Aucun fichier APK trouvé dans $name")
            return AppPackage(file, name, apks, obbs, pkg, label, false)
        }
    }

    /** Produit un APK unique installable et l'enregistre dans Téléchargements. */
    fun toSingleApk(ctx: Context, pkg: AppPackage, p: Progress): Pair<SavedFile, File> {
        if (pkg.isPlainApk) {
            return Storage.save(ctx, pkg.file, pkg.outputBase + ".apk") to pkg.file
        }
        val work = Storage.workDir(ctx, "merge")
        ZipFile(pkg.file).use { zip ->
            if (pkg.apkEntries.size == 1) {
                p.update(0.5f, "Extraction de l'APK…")
                val out = File(work, "app.apk")
                zip.getInputStream(zip.getEntry(pkg.apkEntries[0])).use { i -> out.outputStream().use { i.copyTo(it, 1 shl 20) } }
                return Storage.save(ctx, out, pkg.outputBase + ".apk") to out
            }
            val splitDir = File(work, "splits").apply { mkdirs() }
            pkg.apkEntries.forEachIndexed { i, entry ->
                p.update(0.3f * (i + 1) / pkg.apkEntries.size, "Extraction ${entry.substringAfterLast('/')}…")
                val dest = File(splitDir, entry.substringAfterLast('/'))
                zip.getInputStream(zip.getEntry(entry)).use { inp -> dest.outputStream().use { inp.copyTo(it, 1 shl 20) } }
            }
            val unsigned = File(work, "merged-unsigned.apk")
            p.update(0.4f, "Fusion des ${pkg.apkEntries.size} morceaux en un seul APK…")
            ApkMerger.merge(splitDir, unsigned) { msg -> p.update(0.6f, msg.take(80)) }
            splitDir.deleteRecursively()
            p.update(0.8f, "Signature de l'APK…")
            val signed = File(work, "app.apk")
            val (key, cert) = ResignKey.get()
            ApkMerger.sign(unsigned, signed, key, cert)
            unsigned.delete()
            p.update(0.95f, "Enregistrement…")
            return Storage.save(ctx, signed, pkg.outputBase + ".apk") to signed
        }
    }

    /** Extrait chaque APK (et OBB) du paquet dans Téléchargements/SuperEssence/<nom>. */
    fun extractAll(ctx: Context, pkg: AppPackage): List<SavedFile> {
        val out = ArrayList<SavedFile>()
        ZipFile(pkg.file).use { zip ->
            (pkg.apkEntries + pkg.obbEntries).forEach { entry ->
                val t = Storage.openOutput(ctx, entry.substringAfterLast('/'), pkg.outputBase)
                try {
                    zip.getInputStream(zip.getEntry(entry)).use { t.written = Storage.copyStream(it, t.stream) }
                } catch (e: Throwable) {
                    t.abort(); throw e
                }
                out += t.finish()
            }
        }
        return out
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Installe un APK simple, ou tous les morceaux d'un XAPK/APKS d'un coup. */
    fun install(ctx: Context, pkg: AppPackage?, singleApk: File?, p: Progress) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            if (singleApk != null) {
                session.openWrite("base.apk", 0, singleApk.length()).use { out ->
                    singleApk.inputStream().use { Storage.copyStream(it, out) }
                    session.fsync(out)
                }
            } else {
                requireNotNull(pkg)
                ZipFile(pkg.file).use { zip ->
                    pkg.apkEntries.forEachIndexed { i, entry ->
                        p.update((i + 1).toFloat() / pkg.apkEntries.size, "Préparation de l'installation…")
                        val e = zip.getEntry(entry)
                        session.openWrite("split_$i.apk", 0, e.size).use { out ->
                            zip.getInputStream(e).use { Storage.copyStream(it, out) }
                            session.fsync(out)
                        }
                    }
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                ctx, sessionId, Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName), flags,
            )
            session.commit(pi.intentSender)
        }
    }

    val installResult = MutableStateFlow<String?>(null)
}

/** Clé de signature créée sur l'appareil (jamais stockée dans l'APK ni le dépôt). */
object ResignKey {
    private const val ALIAS = "superessence_resign"

    fun get(): Pair<PrivateKey, X509Certificate> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            val now = System.currentTimeMillis()
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            kpg.initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512, KeyProperties.DIGEST_SHA1)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setKeySize(2048)
                    .setCertificateSubject(X500Principal("CN=SuperEssence Studio, O=SuperEssence Studio"))
                    .setCertificateSerialNumber(BigInteger.valueOf(now))
                    .setCertificateNotBefore(Date(now - 86_400_000L))
                    .setCertificateNotAfter(Date(now + 30L * 365 * 86_400_000L))
                    .build()
            )
            kpg.generateKeyPair()
        }
        val key = ks.getKey(ALIAS, null) as PrivateKey
        val cert = ks.getCertificate(ALIAS) as X509Certificate
        return key to cert
    }
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                ApkTools.installResult.value = "Application installée ✔"
                Toast.makeText(context, "Application installée", Toast.LENGTH_LONG).show()
            }
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "code $status"
                ApkTools.installResult.value = "Échec de l'installation : $msg"
                Toast.makeText(context, "Échec : $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
