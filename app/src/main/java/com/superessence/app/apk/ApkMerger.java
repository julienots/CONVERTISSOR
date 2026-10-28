/*
 * Fusion d'APK fractionnés (XAPK / APKS / APKM) en un seul APK.
 * Logique adaptée de APKEditor (github.com/REAndroid/APKEditor, Apache 2.0).
 */
package com.superessence.app.apk;

import com.android.apksig.ApkSigner;
import com.reandroid.apk.APKLogger;
import com.reandroid.apk.ApkBundle;
import com.reandroid.apk.ApkModule;
import com.reandroid.app.AndroidManifest;
import com.reandroid.archive.ZipEntryMap;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.container.SpecTypePair;
import com.reandroid.arsc.model.ResourceEntry;
import com.reandroid.arsc.value.DataType;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ResValue;

import java.io.File;
import java.io.IOException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Pattern;

public final class ApkMerger {

    public interface Log {
        void message(String msg);
    }

    private ApkMerger() {
    }

    /** Fusionne tous les *.apk du dossier {@code apkDir} dans {@code outApk} (non signé). */
    public static void merge(File apkDir, File outApk, final Log log) throws IOException {
        ApkBundle bundle = new ApkBundle();
        bundle.setAPKLogger(new APKLogger() {
            @Override
            public void logMessage(String msg) {
                log.message(msg);
            }

            @Override
            public void logError(String msg, Throwable tr) {
                log.message(msg);
            }

            @Override
            public void logVerbose(String msg) {
            }
        });
        bundle.loadApkDirectory(apkDir, false);
        log.message("Modules trouvés : " + bundle.getApkModuleList().size());
        ApkModule merged = bundle.mergeModules(false);
        // Supprime les anciennes signatures
        ZipEntryMap entries = merged.getZipEntryMap();
        entries.removeIf(Pattern.compile("^META-INF/.+\\.(([MS]F)|(RSA)|(DSA)|(EC))"));
        entries.remove("stamp-cert-sha256");
        merged.setApkSignatureBlock(null);
        sanitizeManifest(merged);
        merged.refreshTable();
        merged.refreshManifest();
        // Bibliothèques natives compressées acceptées
        merged.setExtractNativeLibs(Boolean.TRUE);
        log.message("Écriture de l'APK…");
        merged.writeApk(outApk);
        merged.close();
        bundle.close();
    }

    /** Signe {@code in} vers {@code out} avec les schémas v1, v2 et v3. */
    public static void sign(File in, File out, PrivateKey key, X509Certificate cert) throws Exception {
        List<X509Certificate> certs = Collections.singletonList(cert);
        ApkSigner.SignerConfig signer = new ApkSigner.SignerConfig.Builder("SUPERESS", key, certs).build();
        List<ApkSigner.SignerConfig> signers = new ArrayList<>();
        signers.add(signer);
        new ApkSigner.Builder(signers)
                .setInputApk(in)
                .setOutputApk(out)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build()
                .sign();
    }

    private static void sanitizeManifest(ApkModule apkModule) {
        if (!apkModule.hasAndroidManifest()) {
            return;
        }
        AndroidManifestBlock manifest = apkModule.getAndroidManifest();
        ResXmlElement manifestElement = manifest.getManifestElement();
        if (manifestElement == null) {
            return;
        }
        manifestElement.removeAttributesWithId(AndroidManifest.ID_requiredSplitTypes);
        manifestElement.removeAttributesWithId(AndroidManifest.ID_splitTypes);
        manifestElement.removeAttributesWithName(AndroidManifest.NAME_requiredSplitTypes);
        manifestElement.removeAttributesWithName(AndroidManifest.NAME_splitTypes);
        manifestElement.removeAttributesWithId(AndroidManifest.ID_isSplitRequired);
        ResXmlElement application = manifest.getApplicationElement();
        if (application == null) {
            manifest.refresh();
            return;
        }
        application.removeAttributesWithId(AndroidManifest.ID_isSplitRequired);
        List<ResXmlElement> toRemove = new ArrayList<>();
        Iterator<ResXmlElement> it = application.getElements(AndroidManifest.TAG_meta_data);
        while (it.hasNext()) {
            ResXmlElement element = it.next();
            ResXmlAttribute nameAttr = element.searchAttributeByResourceId(AndroidManifest.ID_name);
            if (nameAttr == null || nameAttr.getDataType() != DataType.STRING) {
                continue;
            }
            String value = nameAttr.getDataAsString();
            if (value == null) {
                continue;
            }
            if (value.startsWith("com.android.vending.") || value.startsWith("com.android.stamp.")
                    || value.equals("com.android.dynamic.apk.fused.modules")) {
                toRemove.add(element);
            }
        }
        boolean splitsRemoved = false;
        for (ResXmlElement meta : toRemove) {
            if (!splitsRemoved) {
                splitsRemoved = removeSplitsTableEntry(meta, apkModule);
            }
            application.remove(meta);
        }
        manifest.refresh();
    }

    private static boolean removeSplitsTableEntry(ResXmlElement metaElement, ApkModule apkModule) {
        ResXmlAttribute nameAttribute = metaElement.searchAttributeByResourceId(AndroidManifest.ID_name);
        if (nameAttribute == null || !"com.android.vending.splits".equals(nameAttribute.getDataAsString())) {
            return false;
        }
        ResXmlAttribute valueAttribute = metaElement.searchAttributeByResourceId(AndroidManifest.ID_value);
        if (valueAttribute == null) {
            valueAttribute = metaElement.searchAttributeByResourceId(AndroidManifest.ID_resource);
        }
        if (valueAttribute == null || valueAttribute.getDataType() != DataType.REFERENCE
                || !apkModule.hasTableBlock()) {
            return false;
        }
        TableBlock tableBlock = apkModule.getTableBlock();
        ResourceEntry resourceEntry = tableBlock.getResource(valueAttribute.getData());
        if (resourceEntry == null) {
            return false;
        }
        ZipEntryMap zipEntryMap = apkModule.getZipEntryMap();
        for (Entry entry : resourceEntry) {
            if (entry == null) {
                continue;
            }
            ResValue resValue = entry.getResValue();
            if (resValue == null) {
                continue;
            }
            zipEntryMap.remove(resValue.getDataAsString());
            entry.setNull(true);
            SpecTypePair specTypePair = entry.getTypeBlock().getParentSpecTypePair();
            specTypePair.removeNullEntries(entry.getId());
        }
        return true;
    }
}
