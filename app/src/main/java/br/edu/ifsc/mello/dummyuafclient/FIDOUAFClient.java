package br.edu.ifsc.mello.dummyuafclient;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.hardware.fingerprint.FingerprintManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Base64;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.ebayopensource.fido.uaf.client.op.Auth;
import org.ebayopensource.fido.uaf.client.op.Dereg;
import org.ebayopensource.fido.uaf.client.op.Reg;
import org.ebayopensource.fidouafclient.curl.Curl;
import org.ebayopensource.fidouafclient.op.OpUtils;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import br.edu.ifsc.mello.dummyuafclient.fidouafclient.DiscoveryData;
import br.edu.ifsc.mello.dummyuafclient.fidouafclient.ErrorCode;
import br.edu.ifsc.mello.dummyuafclient.fidouafclient.UAFIntentType;

import static br.edu.ifsc.mello.dummyuafclient.fidouafclient.ErrorCode.NO_ERROR;

public class FIDOUAFClient extends AppCompatActivity implements FingerprintUiHelper.Callback {

    public static final int MY_PERMISSIONS_USE_FINGERPRINT = 1;
    private Button mCancelButton;
    private View mFingerprintContent;
    private FingerprintManager.CryptoObject mCryptoObject;
    private FingerprintUiHelper mFingerprintUiHelper;
    private FingerprintUiHelper.FingerprintUiHelperBuilder mFingerprintUiHelperBuilder;
    private KeyguardManager mKeyguardManager;
    private FingerprintManager mFingerprintManager;
    private GetTrustedFacetsTask mGetTrustedTask;
    private ProgressBar mProgressBar;
    private Intent callingIntent;
    private String[] appFacetId;

    //TODO 1 key per RP Server (inheritance from eBay original project) - Move it from SharedPreferences to a HW Keystore+DB;
    private static final String KEY_NAME = "dummy_key";
    private SharedPreferences mSharedPreferences;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fingerprint_dialog_container);

        PreferenceManager.setDefaultValues(this, R.xml.pref_general, false);
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);

        mFingerprintManager = (FingerprintManager) getSystemService(Context.FINGERPRINT_SERVICE);
        mKeyguardManager = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);

        mFingerprintContent = this.findViewById(R.id.fingerprint_container);
        mCancelButton = (Button) this.findViewById(R.id.cancel_button);
        mCancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                uafError(ErrorCode.USER_CANCELLED.getID(), null);
            }
        });
        mProgressBar = (ProgressBar) findViewById(R.id.progressBar);

        mFingerprintUiHelperBuilder = new FingerprintUiHelper.FingerprintUiHelperBuilder(mFingerprintManager);

        mFingerprintUiHelper = mFingerprintUiHelperBuilder.build(
                (ImageView) this.findViewById(R.id.fingerprint_icon),
                (TextView) this.findViewById(R.id.fingerprint_status), this);

        // Are you using a user authentication? No? I'm sorry, you have to.
        if (!mKeyguardManager.isKeyguardSecure()) {
            Toast.makeText(this, getString(R.string.lockscreen), Toast.LENGTH_LONG).show();
            return;
        }
        // If fingerprint authentication is not available
        if (!mFingerprintUiHelper.isFingerprintAuthAvailable(this)) {
            Toast.makeText(this,
                    getString(R.string.fingerprint_use),
                    Toast.LENGTH_LONG).show();
            return;
        }

        mFingerprintContent.setVisibility(View.VISIBLE);
    }

    private void uafError(short errorCode, String uafintentType){
        if (callingIntent != null) {
            Bundle bundle = new Bundle();
            String response = "";
            if (uafintentType != null) {
                bundle.putString("UAFIntentType", uafintentType);
            }
            bundle.putShort("errorCode", errorCode);
            bundle.putString("message", response);
            callingIntent.putExtras(bundle);
            setResult(Activity.RESULT_CANCELED, callingIntent);
        }
        mGetTrustedTask = null;
        finish();
    }

    @Override
    public void onError() {
        uafError(ErrorCode.UNKNOWN.getID(),null);
        showProgress(false);
    }

    @Override
    public void onAuthenticated() {
        this.processUAFIntentType(getIntent());
    }

    public void processUAFIntentType(Intent intent) {
        this.callingIntent = intent;
        Bundle extras = intent.getExtras();
        if (extras != null) {
            String data = (String) extras.get("UAFIntentType");
            if (data != null) {
                if (data.equals(UAFIntentType.DISCOVER.name())) {
                    extras = new Bundle();
                    extras.putString("UAFIntentType", UAFIntentType.DISCOVER_RESULT.name());
                    extras.putShort("errorCode", NO_ERROR.getID());
                    extras.putString("discoveryData", DiscoveryData.getFakeDiscoveryData());
                    intent.putExtras(extras);
                    setResult(Activity.RESULT_OK, intent);
                    finish();
                }
                if (data.equals(UAFIntentType.UAF_OPERATION.name())) {
                    String message = (String) extras.get("message");
                    //TODO According to FIDO Protocol, it contains TLS information to be sent by the FIDO client to the FIDO Server
                    String channelBindings = (String) extras.get("channelBindings");
                    String inMsg = extract(message);

                    if (inMsg.isEmpty()){
                        uafError(ErrorCode.PROTOCOL_ERROR.getID(), UAFIntentType.UAF_OPERATION_RESULT.name());
                        return;
                    }
                    //TODO Move from SharedPreferences to AndroidKeystore. Currently a only one RP is allowed
                    if (inMsg.contains("\"Dereg\"")) {
                        Dereg deregOp = new Dereg();
                        String response = deregOp.dereg(inMsg);
                        extras.putShort("errorCode", ErrorCode.NO_ERROR.getID());
                        extras.putString("message", response);
                        callingIntent.putExtras(extras);
                        setResult(Activity.RESULT_OK, callingIntent);
                        finish();
                    }else {

                        showProgress(true);
                        appFacetId = this.getFacetIdFromCallingIntent();
                        String appId = OpUtils.extractAppId(inMsg);

                        mGetTrustedTask = new GetTrustedFacetsTask();
                        mGetTrustedTask.execute(appId, inMsg);
                    }
                }
            }
        } else {
            finish();
        }
    }



    private void executeFIDOOperations(String inMsg, String trustedFacets) {
        if (trustedFacets.isEmpty()){
            uafError(ErrorCode.PROTOCOL_ERROR.getID(), UAFIntentType.UAF_OPERATION_RESULT.name());
            return;
        }
        Bundle extras = new Bundle();
        String response = "";
        extras.putString("UAFIntentType", UAFIntentType.UAF_OPERATION_RESULT.name());
        String uafRequestMessage = OpUtils.getUafRequest(inMsg, trustedFacets, appFacetId, false);
        // Android RP App is not a trusted facetID
        if (uafRequestMessage.equals(OpUtils.getEmptyUafMsgRegRequest())) {
            extras.putShort("errorCode", ErrorCode.UNTRUSTED_FACET_ID.getID());
            extras.putString("message", response);
            callingIntent.putExtras(extras);
            setResult(Activity.RESULT_CANCELED, callingIntent);
            finish();
        } else {
            if (inMsg.contains("\"Reg\"")) {
                Reg regOp = new Reg();
                response = regOp.register(inMsg);
            } else if (inMsg.contains("\"Auth\"")) {
                Auth authOp = new Auth();
                response = authOp.auth(inMsg);
            }

            extras.putShort("errorCode", NO_ERROR.getID());
            extras.putString("message", response);
            callingIntent.putExtras(extras);
            setResult(Activity.RESULT_OK, callingIntent);
            finish();
        }
    }

    private String[] getFacetIdFromCallingIntent() {
        String[] results = null;
        try {
            PackageInfo packageInfo = this.getPackageManager().getPackageInfo(getCallingPackage(), PackageManager.GET_SIGNATURES);
            results = new String[packageInfo.signatures.length];
            int i = 0;
            for (Signature sign : packageInfo.signatures) {
                MessageDigest messageDigest = MessageDigest.getInstance("SHA1");
                messageDigest.update(sign.toByteArray());
                String currentSignature = Base64.encodeToString(messageDigest.digest(), Base64.DEFAULT);
                String facetID = "android:apk-key-hash:" + currentSignature.substring(0, currentSignature.length() - 2);
                results[i++] = facetID;
            }
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return results;
    }


    private String extract(String inMsg) {
        try {
            JSONObject tmpJson = new JSONObject(inMsg);
            String uafMsg = tmpJson.getString("uafProtocolMessage");
            uafMsg.replace("\\\"", "\"");
            return uafMsg;
        } catch (Exception e) {
            //TODO LOG IT
            return "";
        }

    }

    @Override
    public void onResume() {
        super.onResume();
        mFingerprintUiHelper.startListening(mCryptoObject, this);
    }

    @Override
    public void onPause() {
        super.onPause();
        mFingerprintUiHelper.stopListening();
        uafError(ErrorCode.USER_CANCELLED.getID(),null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String permissions[], int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_USE_FINGERPRINT: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    this.onCreate(null);
                } else {
                    Toast.makeText(this, getString(R.string.fingerprint_permission),
                            Toast.LENGTH_LONG).show();
                }
                return;
            }
        }
    }

    /**
     * Shows the progress UI and hides the login form.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
    private void showProgress(final boolean show) {
        // On Honeycomb MR2 we have the ViewPropertyAnimator APIs, which allow
        // for very easy animations. If available, use these APIs to fade-in
        // the progress spinner.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2) {
            int shortAnimTime = getResources().getInteger(android.R.integer.config_shortAnimTime);

            mProgressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            mProgressBar.animate().setDuration(shortAnimTime).alpha(
                    show ? 1 : 0).setListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mProgressBar.setVisibility(show ? View.VISIBLE : View.GONE);
                }
            });
        } else {
            // The ViewPropertyAnimator APIs are not available, so simply show
            // and hide the relevant UI components.
            mProgressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    // We can't hang user interface, so do get appID in a different thread.
    public class GetTrustedFacetsTask extends AsyncTask<String, Void, String> {
        private String result;
        private String inMsg;

        @Override
        protected String doInBackground(String... params) {
            this.inMsg = params[1];
            result = Curl.get(params[0]).getPayload();
            return result;
        }

        @Override
        protected void onPostExecute(String result) {
            mGetTrustedTask = null;
            this.result = result;
            showProgress(false);
            executeFIDOOperations(this.inMsg, this.result);
        }

        @Override
        protected void onCancelled(String s) {
            super.onCancelled(s);
            mGetTrustedTask = null;
            showProgress(false);
            uafError(ErrorCode.USER_CANCELLED.getID(), null);
        }

        @Override
        protected void onCancelled() {
            mGetTrustedTask = null;
            showProgress(false);
            uafError(ErrorCode.USER_CANCELLED.getID(),null);
        }
    }
}