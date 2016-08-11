package net.opendasharchive.openarchive;


import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.Toast;

import net.opendasharchive.openarchive.EulaActivity.OnEulaAgreedTo;
import io.scal.secureshareui.controller.ArchiveSiteController;
import io.scal.secureshareui.controller.SiteController;
import io.scal.secureshareui.model.Account;

/**
 * Prompt the user to view & agree to the StoryMaker TOS / EULA
 * and present the choice to create a StoryMaker Account.
 * <p/>
 * Should be launched as the start of a new Task, because
 * when this Activity finishes without starting another,
 * it is the result of the user rejecting the TOS / EULA,
 * and so should result in the app exiting.
 *
 * @author micahjlucas
 */
public class FirstStartActivity extends Activity implements OnEulaAgreedTo {

    private static final String TAG = "FirstStartActivity";
    private boolean eulaAgreed = false;
    private Account mAccount = null;

    private SiteController.OnEventListener mAuthEventListener = new SiteController.OnEventListener() {

        @Override
        public void onSuccess(Account account) {
            account.setAuthenticated(true);
            account.saveToSharedPrefs(FirstStartActivity.this, null);
        }

        @Override
        public void onFailure(Account account, String failureMessage) {
            // TODO we should invalidate the locally saved credentials rather than just clearing them
            account.setAuthenticated(false);
            account.saveToSharedPrefs(FirstStartActivity.this, null);
        }

        @Override
        public void onRemove(Account account) {
            Account.clearSharedPreferences(FirstStartActivity.this, null);
            // FIXME do we need to do somehting to clear cookies in the webview? or is that handled for us?
        }
    };



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_sign_in);

        mAccount = new Account(this, null);
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    public void onSignInButtonClick(View v) {
        if (assertTosAccepted()) {
            doAuthentication();
        }
    }

    public void onSignUpButtonClick(View v) {
            doSignUp();
    }

    private void doAuthentication ()
    {
        boolean useTor = ((OpenArchiveApp)getApplication()).getUseTor();

        if (useTor)
            Toast.makeText(this, R.string.orbot_detected,Toast.LENGTH_SHORT).show();

        Intent loginIntent = new Intent(this, MainActivity.class);
        SiteController siteController = SiteController.getSiteController(ArchiveSiteController.SITE_KEY, this, null, null);
        siteController.setUseTor(useTor);
        siteController.setOnEventListener(mAuthEventListener);

        siteController.startAuthentication(mAccount);
    }

    private void doSignUp ()
    {
        boolean useTor = ((OpenArchiveApp)getApplication()).getUseTor();

        if (useTor)
            Toast.makeText(this, R.string.orbot_detected,Toast.LENGTH_SHORT).show();

        Intent loginIntent = new Intent(this, MainActivity.class);
        SiteController siteController = SiteController.getSiteController(ArchiveSiteController.SITE_KEY, this, null, null);
        siteController.setUseTor(useTor);
        siteController.setOnEventListener(mAuthEventListener);
        siteController.startRegistration(mAccount);
    }

    /**
     * Show an AlertDialog prompting the user to
     * accept the EULA / TOS
     *
     * @return
     */
    private boolean assertTosAccepted() {
        return new EulaActivity(this).show();

    }


    @Override
    public void onEulaAgreedTo() {
        doAuthentication ();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
//        super.onActivityResult(requestCode, resultCode, intent); // FIXME do we really need to call up to the super?
        if (requestCode == SiteController.CONTROLLER_REQUEST_CODE) {
            if (resultCode == android.app.Activity.RESULT_OK) {

                mAccount = new Account(this, null);
                String credentials = intent.getStringExtra(SiteController.EXTRAS_KEY_CREDENTIALS);
                mAccount.setCredentials(credentials != null ? credentials : "");

                String username = intent.getStringExtra(SiteController.EXTRAS_KEY_USERNAME);
                mAccount.setUserName(username != null ? username : "");

                String data = intent.getStringExtra(SiteController.EXTRAS_KEY_DATA);
                mAccount.setData(data != null ? data : null);

                mAccount.setAuthenticated(true);
                mAccount.saveToSharedPrefs(this, null);

                Intent mainIntent = new Intent(this, MainActivity.class);
                mainIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(mainIntent);

            }
        }
    }

    public void showAbout(View v)
    {
        Intent intent = new Intent(this, AboutActivity.class);
        startActivity(intent);
    }
}

