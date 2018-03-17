package co.copperhead.attestation;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.AsyncTask;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;

import static android.security.keystore.KeyProperties.DIGEST_SHA256;
import static android.security.keystore.KeyProperties.KEY_ALGORITHM_EC;

public class SubmitSampleJob extends JobService {
    private static final String TAG = "SubmitSampleJob";
    private static final int JOB_ID = 0;
    private static final String SUBMIT_URL = "https://attestation.copperhead.co/submit";
    private static final int CONNECT_TIMEOUT = 60000;
    private static final int READ_TIMEOUT = 60000;

    private static final String KEYSTORE_ALIAS_SAMPLE = "sample_attestation_key";

    private SubmitTask task;

    static void schedule(final Context context) {
        final ComponentName serviceName = new ComponentName(context, SubmitSampleJob.class);
        final JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        final int result = scheduler.schedule(new JobInfo.Builder(JOB_ID, serviceName)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build());
        if (result == JobScheduler.RESULT_FAILURE) {
            Log.d(TAG, "job schedule failed");
        }
    }

    private class SubmitTask extends AsyncTask<Void, Void, Boolean> {
        final JobParameters params;

        SubmitTask(final JobParameters params) {
            this.params = params;
        }

        @Override
        protected void onPostExecute(final Boolean success) {
            jobFinished(params, success);
        }

        @Override
        protected Boolean doInBackground(final Void... params) {
            try {
                final HttpURLConnection connection = (HttpURLConnection) new URL(SUBMIT_URL).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT);
                connection.setReadTimeout(READ_TIMEOUT);
                connection.setDoOutput(true);

                final KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
                keyStore.load(null);

                final KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(KEYSTORE_ALIAS_SAMPLE,
                        KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                        .setAlgorithmParameterSpec(new ECGenParameterSpec(AttestationProtocol.EC_CURVE))
                        .setDigests(AttestationProtocol.KEY_DIGEST)
                        .setAttestationChallenge("sample".getBytes());
                AttestationProtocol.generateKeyPair(KEY_ALGORITHM_EC, builder.build());
                final Certificate[] certs = keyStore.getCertificateChain(KEYSTORE_ALIAS_SAMPLE);
                keyStore.deleteEntry(KEYSTORE_ALIAS_SAMPLE);

                final Process process = new ProcessBuilder("getprop").start();
                final InputStream propertyStream = process.getInputStream();

                final OutputStream output = connection.getOutputStream();
                for (final Certificate cert : certs) {
                    output.write(BaseEncoding.base64().encode(cert.getEncoded()).getBytes());
                    output.write("\n".getBytes());
                }
                ByteStreams.copy(propertyStream, output);
                propertyStream.close();
                output.close();

                final int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    throw new IOException("response code: " + responseCode);
                }

                connection.disconnect();
            } catch (final GeneralSecurityException | IOException e) {
                Log.e(TAG, "submit failure", e);
                return true;
            }
            return false;
        }
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        Log.d(TAG, "start job");
        task = new SubmitTask(params);
        task.execute();
        return true;
    }

    @Override
    public boolean onStopJob(final JobParameters params) {
        task.cancel(true);
        return true;
    }
}