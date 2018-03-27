package co.copperhead.attestation;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.text.Html;
import android.text.Spanned;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.common.collect.ImmutableSet;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.io.UnsupportedEncodingException;
import java.util.EnumMap;
import java.util.Map;

import static android.graphics.Color.BLACK;
import static android.graphics.Color.WHITE;

public class AttestationActivity extends AppCompatActivity {
    private static final String TAG = "AttestationActivity";

    private static final String STATE_AUDITEE_PAIRING = "auditee_pairing";
    private static final String STATE_AUDITEE_SERIALIZED_ATTESTATION = "auditee_serialized_attestation";
    private static final String STATE_AUDITOR_CHALLENGE = "auditor_challenge";
    private static final String STATE_STAGE = "stage";
    private static final String STATE_OUTPUT = "output";
    private static final String STATE_BACKGROUND_RESOURCE = "background_resource";

    private static final int GENERATE_REQUEST_CODE = 0;
    private static final int VERIFY_REQUEST_CODE = 1;
    private static final int SCAN_REQUEST_CODE = 2;

    private static final int PERMISSIONS_REQUEST_CAMERA = 10;

    private TextView textView;
    private ImageView imageView;
    private View buttons;
    private Snackbar snackbar;

    private enum Stage {
        None,
        Auditee,
        AuditeeGenerate,
        AuditeeResults,
        Auditor,
        AuditorResults,
        EnableRemoteVerify
    }

    private Stage mStage = Stage.None;
    private boolean auditeePairing;
    private byte[] auditeeSerializedAttestation;
    private byte[] auditorChallenge;
    private int backgroundResource;

    private static final ImmutableSet<String> supportedModels = ImmutableSet.of(
            "BKL-L04", "H3113", "H4113", "Pixel 2", "Pixel 2 XL", "SM-G960U", "SM-G965F", "SM-G965U1", "SM-G965W");
    private static final boolean isSupportedAuditee = supportedModels.contains(Build.MODEL);

    private static int getFirstApiLevel() {
        return Integer.parseInt(SystemProperties.get("ro.product.first_api_level",
                Integer.toString(Build.VERSION.SDK_INT)));
    }

    private static boolean potentialSupportedAuditee() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                getFirstApiLevel() >= Build.VERSION_CODES.O;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_attestation);
        setSupportActionBar(findViewById(R.id.toolbar));

        buttons = findViewById(R.id.buttons);
        snackbar = Snackbar.make(findViewById(R.id.content_attestation), "", Snackbar.LENGTH_LONG);

        findViewById(R.id.auditee).setOnClickListener((final View view) -> {
            if (!isSupportedAuditee) {
                snackbar.setText(R.string.unsupported_auditee).show();
                return;
            }
            mStage = Stage.Auditee;
            showQrScanner();
        });

        findViewById(R.id.auditor).setOnClickListener(view -> {
            snackbar.dismiss();
            mStage = Stage.Auditor;
            buttons.setVisibility(View.GONE);
            runAuditor();
        });

        textView = findViewById(R.id.textview);
        textView.setMovementMethod(new ScrollingMovementMethod());

        imageView = findViewById(R.id.imageview);

        if (savedInstanceState != null) {
            auditeePairing = savedInstanceState.getBoolean(STATE_AUDITEE_PAIRING);
            auditeeSerializedAttestation = savedInstanceState.getByteArray(STATE_AUDITEE_SERIALIZED_ATTESTATION);
            auditorChallenge = savedInstanceState.getByteArray(STATE_AUDITOR_CHALLENGE);
            mStage = Stage.valueOf(savedInstanceState.getString(STATE_STAGE));
            textView.setText(Html.fromHtml(savedInstanceState.getString(STATE_OUTPUT),
                    Html.FROM_HTML_MODE_LEGACY));
            backgroundResource = savedInstanceState.getInt(STATE_BACKGROUND_RESOURCE);
        }

        final ViewTreeObserver vto = imageView.getViewTreeObserver();
        vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                imageView.getViewTreeObserver().removeOnPreDrawListener(this);
                if (mStage != Stage.None) {
                    buttons.setVisibility(View.GONE);
                    if (mStage == Stage.AuditeeResults) {
                        auditeeShowAttestation(auditeeSerializedAttestation);
                    } else if (mStage == Stage.Auditor) {
                        runAuditor();
                    }
                }
                findViewById(R.id.content_attestation).setBackgroundResource(backgroundResource);
                return true;
            }
        });
    }

    @Override
    public void onSaveInstanceState(final Bundle savedInstanceState) {
        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putBoolean(STATE_AUDITEE_PAIRING, auditeePairing);
        savedInstanceState.putByteArray(STATE_AUDITEE_SERIALIZED_ATTESTATION, auditeeSerializedAttestation);
        savedInstanceState.putByteArray(STATE_AUDITOR_CHALLENGE, auditorChallenge);
        savedInstanceState.putString(STATE_STAGE, mStage.name());
        savedInstanceState.putString(STATE_OUTPUT, Html.toHtml((Spanned) textView.getText(),
                Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE));
        savedInstanceState.putInt(STATE_BACKGROUND_RESOURCE, backgroundResource);
    }

    private void chooseBestLayout() {
        final View content = findViewById(R.id.content_attestation);
        final LinearLayout resultLayout = findViewById(R.id.result);
        if (content.getHeight() - textView.getHeight() > content.getWidth() - textView.getWidth()) {
            resultLayout.setOrientation(LinearLayout.VERTICAL);
        }
    }

    private void runAuditor() {
        if (auditorChallenge == null) {
            auditorChallenge = AttestationProtocol.getChallengeMessage(this);
        }
        Log.d(TAG, "sending random challenge: " + Utils.logFormatBytes(auditorChallenge));
        textView.setText(R.string.qr_code_scan_hint_auditor);
        chooseBestLayout();

        final ViewTreeObserver vto = imageView.getViewTreeObserver();
        vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                imageView.getViewTreeObserver().removeOnPreDrawListener(this);
                imageView.setImageBitmap(createQrCode(auditorChallenge));
                return true;
            }
        });

        imageView.setOnClickListener(view -> showQrScanner());
    }

    private void showAuditorResults(final byte[] serialized) {
        Log.d(TAG, "received attestation: " + Utils.logFormatBytes(serialized));
        textView.setText(R.string.verifying_attestation);
        final PendingIntent pending = createPendingResult(VERIFY_REQUEST_CODE, new Intent(), 0);
        final Intent intent = new Intent(this, VerifyAttestationService.class);
        intent.putExtra(VerifyAttestationService.EXTRA_CHALLENGE_MESSAGE, auditorChallenge);
        intent.putExtra(VerifyAttestationService.EXTRA_SERIALIZED, serialized);
        intent.putExtra(VerifyAttestationService.EXTRA_PENDING_RESULT, pending);
        startService(intent);
    }

    private void continueAuditee(final byte[] challenge) {
        Log.d(TAG, "received random challenge: " + Utils.logFormatBytes(challenge));
        textView.setText(R.string.generating_attestation);
        final PendingIntent pending = createPendingResult(GENERATE_REQUEST_CODE, new Intent(), 0);
        final Intent intent = new Intent(this, GenerateAttestationService.class);
        intent.putExtra(GenerateAttestationService.EXTRA_CHALLENGE_MESSAGE, challenge);
        intent.putExtra(GenerateAttestationService.EXTRA_PENDING_RESULT, pending);
        startService(intent);
    }

    private void auditeeShowAttestation(final byte[] serialized) {
        Log.d(TAG, "sending attestation: " + Utils.logFormatBytes(serialized));
        auditeeSerializedAttestation = serialized;
        mStage = Stage.AuditeeResults;
        if (auditeePairing) {
            textView.setText(R.string.qr_code_scan_hint_auditee_pairing);
        } else {
            textView.setText(R.string.qr_code_scan_hint_auditee);
        }
        chooseBestLayout();

        final ViewTreeObserver vto = imageView.getViewTreeObserver();
        vto.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                imageView.getViewTreeObserver().removeOnPreDrawListener(this);
                imageView.setImageBitmap(createQrCode(serialized));
                return true;
            }
        });
    }

    private Bitmap createQrCode(final byte[] contents) {
        final BitMatrix result;
        try {
            final QRCodeWriter writer = new QRCodeWriter();
            final Map<EncodeHintType,Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "ISO-8859-1");
            try {
                final int size = Math.min(imageView.getWidth(), imageView.getHeight());
                result = writer.encode(new String(contents, "ISO-8859-1"), BarcodeFormat.QR_CODE,
                        size, size, hints);
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException("ISO-8859-1 not supported", e);
            }
        } catch (WriterException e) {
            throw new RuntimeException(e);
        }

        final int width = result.getWidth();
        final int height = result.getHeight();
        final int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            final int offset = y * width;
            for (int x = 0; x < width; x++) {
                pixels[offset + x] = result.get(x, y) ? BLACK : WHITE;
            }
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.RGB_565);
    }

    private void showQrScanner() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA},
                    PERMISSIONS_REQUEST_CAMERA);
        } else {
            startActivityForResult(new Intent(this, QRScannerActivity.class), SCAN_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String permissions[], @NonNull int[] grantResults) {
        switch (requestCode) {
            case PERMISSIONS_REQUEST_CAMERA: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    snackbar.dismiss();
                    startActivityForResult(new Intent(this, QRScannerActivity.class), SCAN_REQUEST_CODE);
                } else {
                    snackbar.setText(R.string.camera_permission_denied).show();
                }
            }
        }
    }

    private void setBackgroundResource(final int resid) {
        final View content = findViewById(R.id.content_attestation);
        backgroundResource = resid;
        content.setBackgroundResource(resid);
    }

    @Override
    public void onActivityResult(final int requestCode, final int resultCode, final Intent intent) {
        Log.d(TAG, "onActivityResult " + requestCode + " " + resultCode);

        if (requestCode == GENERATE_REQUEST_CODE) {
            if (resultCode != GenerateAttestationService.RESULT_CODE) {
                throw new RuntimeException("unexpected result code");
            }
            if (intent.hasExtra(GenerateAttestationService.EXTRA_ATTESTATION_ERROR)) {
                setBackgroundResource(R.color.red200);
                textView.setText(R.string.generate_error);
                textView.append(intent.getStringExtra(GenerateAttestationService.EXTRA_ATTESTATION_ERROR));
                return;
            }
            auditeePairing = intent.getBooleanExtra(GenerateAttestationService.EXTRA_PAIRING, false);
            auditeeShowAttestation(intent.getByteArrayExtra(GenerateAttestationService.EXTRA_ATTESTATION));
        } else if (requestCode == VERIFY_REQUEST_CODE) {
            if (resultCode != VerifyAttestationService.RESULT_CODE) {
                throw new RuntimeException("unexpected result code");
            }
            if (intent.hasExtra(VerifyAttestationService.EXTRA_ERROR)) {
                setBackgroundResource(R.color.red200);
                textView.setText(R.string.verify_error);
                textView.append(intent.getStringExtra(VerifyAttestationService.EXTRA_ERROR));
                return;
            }
            final boolean strong = intent.getBooleanExtra(VerifyAttestationService.EXTRA_STRONG, false);
            setBackgroundResource(strong ? R.color.green200 : R.color.orange200);
            textView.setText(strong ? R.string.verify_strong : R.string.verify_basic);
            textView.append(getText(R.string.device_information));
            textView.append(intent.getStringExtra(VerifyAttestationService.EXTRA_TEE_ENFORCED));
            textView.append(getText(R.string.os_enforced));
            textView.append(intent.getStringExtra(VerifyAttestationService.EXTRA_OS_ENFORCED));
        } else if (requestCode == SCAN_REQUEST_CODE) {
            if (intent != null) {
                // handle scan result
                final String contents = intent.getStringExtra("SCAN_RESULT");
                if (contents == null) {
                    if (mStage == Stage.Auditee) {
                        mStage = Stage.None;
                    }
                    return;
                }
                final byte[] contentsBytes;
                try {
                    contentsBytes = contents.getBytes("ISO-8859-1");
                } catch (UnsupportedEncodingException e) {
                    throw new RuntimeException("ISO-8859-1 not supported", e);
                }
                if (mStage == Stage.Auditee) {
                    mStage = Stage.AuditeeGenerate;
                    buttons.setVisibility(View.GONE);
                    continueAuditee(contentsBytes);
                } else if (mStage == Stage.Auditor) {
                    mStage = Stage.AuditorResults;
                    imageView.setVisibility(View.GONE);
                    showAuditorResults(contentsBytes);
                } else if (mStage == Stage.EnableRemoteVerify) {
                    mStage = Stage.None;
                    Log.d(TAG, "account: " + contents);
                    final String[] values = contents.split(" ");
                    if (values.length < 3 || !RemoteVerifyJob.DOMAIN.equals(values[0])) {
                        snackbar.setText(R.string.scanned_invalid_account_qr_code).show();
                        return;
                    }
                    PreferenceManager.getDefaultSharedPreferences(this)
                            .edit().putString(RemoteVerifyJob.KEY_REMOTE_ACCOUNT, values[1]).apply();
                    try {
                        if (RemoteVerifyJob.schedule(this, Integer.parseInt(values[2]))) {
                            snackbar.setText(R.string.enable_remote_verify).show();
                        }
                    } catch (final RemoteVerifyJob.InvalidInterval | NumberFormatException e) {
                        snackbar.setText(R.string.scanned_invalid_account_qr_code).show();
                    }
                } else {
                    throw new RuntimeException("received unexpected scan result");
                }
            } else {
                if (mStage == Stage.Auditee) {
                    mStage = Stage.None;
                }
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.menu_attestation, menu);
        menu.findItem(R.id.action_clear_auditee).setEnabled(isSupportedAuditee);
        menu.findItem(R.id.action_submit_sample).setEnabled(potentialSupportedAuditee());
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(final Menu menu) {
        final boolean isScheduled = RemoteVerifyJob.isScheduled(this);
        menu.findItem(R.id.action_enable_remote_verify)
                .setEnabled(isSupportedAuditee && !isScheduled);
        menu.findItem(R.id.action_disable_remote_verify).setEnabled(isScheduled);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        switch (item.getItemId()) {
            case R.id.action_clear_auditee: {
                final Intent intent = new Intent(this, GenerateAttestationService.class);
                intent.putExtra(GenerateAttestationService.EXTRA_CLEAR, true);
                startService(intent);
                return true;
            }
            case R.id.action_clear_auditor: {
                final Intent intent = new Intent(this, VerifyAttestationService.class);
                intent.putExtra(VerifyAttestationService.EXTRA_CLEAR, true);
                startService(intent);
                return true;
            }
            case R.id.action_enable_remote_verify: {
                mStage = Stage.EnableRemoteVerify;
                showQrScanner();
                return true;
            }
            case R.id.action_disable_remote_verify: {
                RemoteVerifyJob.cancel(this);
                final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
                preferences.edit().remove(RemoteVerifyJob.KEY_REMOTE_ACCOUNT).apply();
                snackbar.setText(R.string.disable_remote_verify).show();
                return true;
            }
            case R.id.action_submit_sample: {
                snackbar.setText(R.string.schedule_submit_sample).show();
                SubmitSampleJob.schedule(this);
                return true;
            }
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (mStage == Stage.AuditeeResults || mStage == Stage.Auditor ||
                mStage == Stage.AuditorResults) {
            auditeeSerializedAttestation = null;
            auditorChallenge = null;
            mStage = Stage.None;
            textView.setText("");
            backgroundResource = 0;
            recreate();
            return;
        }
        super.onBackPressed();
    }
}
