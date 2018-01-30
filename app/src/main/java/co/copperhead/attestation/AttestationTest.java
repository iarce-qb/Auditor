package co.copperhead.attestation;

import com.google.common.io.BaseEncoding;

import android.os.AsyncTask;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;
import android.widget.TextView;

import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.SignatureException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Date;

import java.security.cert.X509Certificate;

import static android.security.keystore.KeyProperties.DIGEST_SHA256;
import static android.security.keystore.KeyProperties.KEY_ALGORITHM_EC;

/**
 * AttestationTest generates an EC Key pair, with attestation, and displays the result in the
 * TextView provided to its constructor.
 */
public class AttestationTest extends AsyncTask<Void, String, Void> {
    private static final int ORIGINATION_TIME_OFFSET = 1000000;
    private static final int CONSUMPTION_TIME_OFFSET = 2000000;

    private static final int KEY_USAGE_BITSTRING_LENGTH = 9;
    private static final int KEY_USAGE_DIGITAL_SIGNATURE_BIT_OFFSET = 0;
    private static final int KEY_USAGE_KEY_ENCIPHERMENT_BIT_OFFSET = 2;
    private static final int KEY_USAGE_DATA_ENCIPHERMENT_BIT_OFFSET = 3;

    private static final int KM_ERROR_INVALID_INPUT_LENGTH = -21;
    private final TextView view;

    AttestationTest(TextView view) {
        this.view = view;
    }

    private static final String GOOGLE_ROOT_CERTIFICATE =
            "-----BEGIN CERTIFICATE-----\n"
                    + "MIIFYDCCA0igAwIBAgIJAOj6GWMU0voYMA0GCSqGSIb3DQEBCwUAMBsxGTAXBgNV"
                    + "BAUTEGY5MjAwOWU4NTNiNmIwNDUwHhcNMTYwNTI2MTYyODUyWhcNMjYwNTI0MTYy"
                    + "ODUyWjAbMRkwFwYDVQQFExBmOTIwMDllODUzYjZiMDQ1MIICIjANBgkqhkiG9w0B"
                    + "AQEFAAOCAg8AMIICCgKCAgEAr7bHgiuxpwHsK7Qui8xUFmOr75gvMsd/dTEDDJdS"
                    + "Sxtf6An7xyqpRR90PL2abxM1dEqlXnf2tqw1Ne4Xwl5jlRfdnJLmN0pTy/4lj4/7"
                    + "tv0Sk3iiKkypnEUtR6WfMgH0QZfKHM1+di+y9TFRtv6y//0rb+T+W8a9nsNL/ggj"
                    + "nar86461qO0rOs2cXjp3kOG1FEJ5MVmFmBGtnrKpa73XpXyTqRxB/M0n1n/W9nGq"
                    + "C4FSYa04T6N5RIZGBN2z2MT5IKGbFlbC8UrW0DxW7AYImQQcHtGl/m00QLVWutHQ"
                    + "oVJYnFPlXTcHYvASLu+RhhsbDmxMgJJ0mcDpvsC4PjvB+TxywElgS70vE0XmLD+O"
                    + "JtvsBslHZvPBKCOdT0MS+tgSOIfga+z1Z1g7+DVagf7quvmag8jfPioyKvxnK/Eg"
                    + "sTUVi2ghzq8wm27ud/mIM7AY2qEORR8Go3TVB4HzWQgpZrt3i5MIlCaY504LzSRi"
                    + "igHCzAPlHws+W0rB5N+er5/2pJKnfBSDiCiFAVtCLOZ7gLiMm0jhO2B6tUXHI/+M"
                    + "RPjy02i59lINMRRev56GKtcd9qO/0kUJWdZTdA2XoS82ixPvZtXQpUpuL12ab+9E"
                    + "aDK8Z4RHJYYfCT3Q5vNAXaiWQ+8PTWm2QgBR/bkwSWc+NpUFgNPN9PvQi8WEg5Um"
                    + "AGMCAwEAAaOBpjCBozAdBgNVHQ4EFgQUNmHhAHyIBQlRi0RsR/8aTMnqTxIwHwYD"
                    + "VR0jBBgwFoAUNmHhAHyIBQlRi0RsR/8aTMnqTxIwDwYDVR0TAQH/BAUwAwEB/zAO"
                    + "BgNVHQ8BAf8EBAMCAYYwQAYDVR0fBDkwNzA1oDOgMYYvaHR0cHM6Ly9hbmRyb2lk"
                    + "Lmdvb2dsZWFwaXMuY29tL2F0dGVzdGF0aW9uL2NybC8wDQYJKoZIhvcNAQELBQAD"
                    + "ggIBACDIw41L3KlXG0aMiS//cqrG+EShHUGo8HNsw30W1kJtjn6UBwRM6jnmiwfB"
                    + "Pb8VA91chb2vssAtX2zbTvqBJ9+LBPGCdw/E53Rbf86qhxKaiAHOjpvAy5Y3m00m"
                    + "qC0w/Zwvju1twb4vhLaJ5NkUJYsUS7rmJKHHBnETLi8GFqiEsqTWpG/6ibYCv7rY"
                    + "DBJDcR9W62BW9jfIoBQcxUCUJouMPH25lLNcDc1ssqvC2v7iUgI9LeoM1sNovqPm"
                    + "QUiG9rHli1vXxzCyaMTjwftkJLkf6724DFhuKug2jITV0QkXvaJWF4nUaHOTNA4u"
                    + "JU9WDvZLI1j83A+/xnAJUucIv/zGJ1AMH2boHqF8CY16LpsYgBt6tKxxWH00XcyD"
                    + "CdW2KlBCeqbQPcsFmWyWugxdcekhYsAWyoSf818NUsZdBWBaR/OukXrNLfkQ79Iy"
                    + "ZohZbvabO/X+MVT3rriAoKc8oE2Uws6DF+60PV7/WIPjNvXySdqspImSN78mflxD"
                    + "qwLqRBYkA3I75qppLGG9rp7UCdRjxMl8ZDBld+7yvHVgt1cVzJx9xnyGCC23Uaic"
                    + "MDSXYrB4I4WHXPGjxhZuCuPBLTdOLU8YRvMYdEvYebWHMpvwGCF6bAx3JBpIeOQ1"
                    + "wDB5y0USicV3YgYGmi+NZfhA4URSh77Yd6uuJOJENRaNVTzk\n"
                    + "-----END CERTIFICATE-----";

    private static final String COPPERHEADOS_FINGERPRINT_TAIMEN =
            "815DCBA82BAC1B1758211FF53CAA0B6883CB6C901BE285E1B291C8BDAA12DF75";
    private static final String COPPERHEADOS_FINGERPRINT_WALLEYE =
            "36D067F8517A2284781B99A2984966BFF02D3F47310F831FCDCC4D792426B6DF";

    @Override
    protected Void doInBackground(Void... params) {
        try {
            testEcAttestation();
        } catch (Exception e) {
            StringWriter s = new StringWriter();
            e.printStackTrace(new PrintWriter(s));
            publishProgress(s.toString());
        }
        return null;
    }

    @Override
    protected void onProgressUpdate(String... values) {
        for (String value : values) {
            view.append(value);
        }
    }

    private byte[] getChallenge() {
        final SecureRandom random = new SecureRandom();
        final byte[] challenge = new byte[32];
        random.nextBytes(challenge);
        return challenge;
    }

    private void verifyAttestation(final Certificate certificates[], final byte[] challenge) throws GeneralSecurityException {
        verifyCertificateSignatures(certificates);

        X509Certificate attestationCert = (X509Certificate) certificates[0];
        X509Certificate secureRoot = (X509Certificate) CertificateFactory
                .getInstance("X.509").generateCertificate(
                        new ByteArrayInputStream(
                                GOOGLE_ROOT_CERTIFICATE.getBytes()));
        X509Certificate rootCert = (X509Certificate) certificates[certificates.length - 1];
        if (!Arrays.equals(secureRoot.getEncoded(), rootCert.getEncoded())) {
            throw new GeneralSecurityException("root certificate is not the Google root");
        }
        //printKeyUsage(attestationCert);

        Attestation attestation = new Attestation(attestationCert);

        // prevent replay attacks
        if (!Arrays.equals(attestation.getAttestationChallenge(), challenge)) {
            throw new GeneralSecurityException("challenge mismatch");
        }

        // version sanity checks
        if (attestation.getAttestationVersion() < 2) {
            throw new GeneralSecurityException("attestation version below 2");
        }
        if (attestation.getAttestationSecurityLevel() != Attestation.KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT) {
            throw new GeneralSecurityException("attestation security level is software");
        }
        if (attestation.getKeymasterVersion() < 3) {
            throw new GeneralSecurityException("keymaster version below 3");
        }
        if (attestation.getKeymasterSecurityLevel() != Attestation.KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT) {
            throw new GeneralSecurityException("keymaster security level is software");
        }

        final AuthorizationList teeEnforced = attestation.getTeeEnforced();

        // key sanity checks
        if (teeEnforced.getOrigin() != AuthorizationList.KM_ORIGIN_GENERATED) {
            throw new GeneralSecurityException("not a generated key");
        }
        if (!teeEnforced.isRollbackResistant()) {
            throw new GeneralSecurityException("expected rollback resistant key");
        }

        // verified boot security checks
        final RootOfTrust rootOfTrust = teeEnforced.getRootOfTrust();
        if (rootOfTrust == null) {
            throw new GeneralSecurityException("missing root of trust");
        }
        if (!rootOfTrust.isDeviceLocked()) {
            throw new GeneralSecurityException("device is not locked");
        }
        if (rootOfTrust.getVerifiedBootState() != RootOfTrust.KM_VERIFIED_BOOT_SELF_SIGNED) {
            throw new GeneralSecurityException("verified boot state is not self signed");
        }
        final String verifiedBootKey = BaseEncoding.base16().encode(rootOfTrust.getVerifiedBootKey());
        String device = null;
        if (verifiedBootKey.equals(COPPERHEADOS_FINGERPRINT_TAIMEN)) {
            device = "Pixel 2 XL";
        } else if (verifiedBootKey.equals(COPPERHEADOS_FINGERPRINT_WALLEYE)) {
            device = "Pixel 2";
        }
        if (device == null) {
            throw new GeneralSecurityException("invalid key fingerprint");
        }

        publishProgress("Successfully verified CopperheadOS attestation.\n\n");

        publishProgress("Device: " + device + "\n");

        final String osVersion = String.format("%06d", teeEnforced.getOsVersion());
        publishProgress("OS version: " +
                Integer.parseInt(osVersion.substring(0, 2)) + "." +
                Integer.parseInt(osVersion.substring(2, 4)) + "." +
                Integer.parseInt(osVersion.substring(4, 6)) + "\n");

        final String osPatchLevel = teeEnforced.getOsPatchLevel().toString();
        publishProgress("OS patch level: " + osPatchLevel.substring(0, 4) + "-" + osPatchLevel.substring(4, 6) + "\n");

        //publishProgress("\n\n\n\n" + attestation.toString() + "\n");
    }

    private void testEcAttestation() throws Exception {
        String ecCurve = "secp256r1";
        int keySize = 256;
        String keystoreAlias = "fresh_attestation_key";

        // this will be done by another device running the app to verify this one
        final byte[] challenge = getChallenge();

        String persistentKeystoreAlias = "persistent_attestation_key";

        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        keyStore.deleteEntry(keystoreAlias);

        {
            Date startTime = new Date(new Date().getTime() - 1000);
            Log.d("****", "Start Time is: " + startTime.toString());
            Date originationEnd = new Date(startTime.getTime() + ORIGINATION_TIME_OFFSET);
            Date consumptionEnd = new Date(startTime.getTime() + CONSUMPTION_TIME_OFFSET);
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(keystoreAlias,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec(ecCurve))
                    .setDigests(DIGEST_SHA256)
                    .setAttestationChallenge(challenge)
                    .setKeyValidityStart(startTime)
                    .setKeyValidityForOriginationEnd(originationEnd)
                    .setKeyValidityForConsumptionEnd(consumptionEnd);

            generateKeyPair(KEY_ALGORITHM_EC, builder.build());
        }

        final boolean hasPersistentKey = keyStore.containsAlias(persistentKeystoreAlias);
        if (!hasPersistentKey) {
            Date startTime = new Date(new Date().getTime() - 1000);
            Log.d("****", "Start Time is: " + startTime.toString());
            Date originationEnd = new Date(startTime.getTime() + ORIGINATION_TIME_OFFSET);
            Date consumptionEnd = new Date(startTime.getTime() + CONSUMPTION_TIME_OFFSET);
            KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(persistentKeystoreAlias,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new ECGenParameterSpec(ecCurve))
                    .setDigests(DIGEST_SHA256)
                    .setAttestationChallenge(challenge)
                    .setKeyValidityStart(startTime);

            generateKeyPair(KEY_ALGORITHM_EC, builder.build());
        }

        // this will be done by another device running the app to verify this one
        publishProgress("Verifying ephemeral key...\n\n");
        verifyAttestation(keyStore.getCertificateChain(keystoreAlias), challenge);

        publishProgress("\n------------------\n\n");

        if (hasPersistentKey) {
            publishProgress("Checking persistent key...\n\n");

            Signature signer = Signature.getInstance("SHA256WithECDSA");
            KeyStore keystore = KeyStore.getInstance("AndroidKeyStore");
            keystore.load(null);

            PrivateKey key = (PrivateKey) keystore.getKey(persistentKeystoreAlias, null);
            signer.initSign(key);
            signer.update("Hello".getBytes());
            byte[] signature = signer.sign();

            publishProgress("\nSuccessfully verified expected device.\n\n");
        } else {
            publishProgress("Verifying persistent key...\n\n");
            verifyAttestation(keyStore.getCertificateChain(persistentKeystoreAlias), challenge);
        }
    }

    private void generateKeyPair(String algorithm, KeyGenParameterSpec spec)
            throws NoSuchAlgorithmException, NoSuchProviderException,
            InvalidAlgorithmParameterException {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance(algorithm,
                "AndroidKeyStore");
        keyPairGenerator.initialize(spec);
        keyPairGenerator.generateKeyPair();
    }

    private void verifyCertificateSignatures(Certificate[] certChain)
            throws GeneralSecurityException {

        for (Certificate cert : certChain) {
            final byte[] derCert = cert.getEncoded();
            final String pemCertPre = Base64.encodeToString(derCert, Base64.NO_WRAP);
            Log.e("****", pemCertPre);
        }

        for (int i = 1; i < certChain.length; ++i) {
            PublicKey pubKey = certChain[i].getPublicKey();
            try {
                certChain[i - 1].verify(pubKey);
            } catch (InvalidKeyException | CertificateException | NoSuchAlgorithmException
                    | NoSuchProviderException | SignatureException e) {
                throw new GeneralSecurityException("Failed to verify certificate "
                        + certChain[i - 1] + " with public key " + certChain[i].getPublicKey(), e);
            }
            if (i == certChain.length - 1) {
                // Last cert is self-signed.
                try {
                    certChain[i].verify(pubKey);
                } catch (CertificateException e) {
                    throw new GeneralSecurityException(
                            "Root cert " + certChain[i] + " is not correctly self-signed", e);
                }
            }
        }
    }

    private void printKeyUsage(X509Certificate attestationCert) {
        publishProgress("Key usage:");
        if (attestationCert.getKeyUsage() == null) {
            publishProgress(" NONE\n");
            return;
        }
        if (attestationCert.getKeyUsage()[KEY_USAGE_DIGITAL_SIGNATURE_BIT_OFFSET]) {
            publishProgress(" sign");
        }
        if (attestationCert.getKeyUsage()[KEY_USAGE_DATA_ENCIPHERMENT_BIT_OFFSET]) {
            publishProgress(" encrypt_data");
        }
        if (attestationCert.getKeyUsage()[KEY_USAGE_KEY_ENCIPHERMENT_BIT_OFFSET]) {
            publishProgress(" encrypt_keys");
        }
        publishProgress("\n");
    }
}
