package net.opendasharchive.openarchive.nearby;

import android.app.ProgressDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.design.widget.Snackbar;
import android.support.v7.widget.SwitchCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.amulyakhare.textdrawable.TextDrawable;
import com.github.lzyzsd.circleprogress.CircleProgress;
import com.github.lzyzsd.circleprogress.DonutProgress;
import com.ramimartin.multibluetooth.activity.BluetoothActivity;
import com.ramimartin.multibluetooth.activity.BluetoothFragmentActivity;
import com.ramimartin.multibluetooth.bluetooth.mananger.BluetoothManager;
import com.simonguest.btxfr.ClientThread;
import com.simonguest.btxfr.MessageType;
import com.simonguest.btxfr.ProgressData;
import com.simonguest.btxfr.ServerThread;
import com.simonguest.btxfr.Utils;

import net.opendasharchive.openarchive.Globals;
import net.opendasharchive.openarchive.MainActivity;
import net.opendasharchive.openarchive.R;
import net.opendasharchive.openarchive.db.Media;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;

import de.greenrobot.event.EventBus;
import de.greenrobot.event.util.ExceptionToResourceMapping;

public class NearbyActivity extends BluetoothFragmentActivity {


    private TextView mTvNearbyLog;
    private DonutProgress mProgress;
    private LinearLayout mViewNearbyDevices;
    private SwitchCompat mSwitchPairedOnly;

    private boolean mIsServer = false;
    private ServerThread serverThread = null;

    private Media mMedia = null;

    private boolean mPairedDevicesOnly = false;

    private static HashMap<String,BluetoothDevice> mFoundDevices = new HashMap<String,BluetoothDevice>();
    private HashMap<String, ClientThread> clientThreads = new HashMap<String, ClientThread>();

    private Handler mHandler = new Handler ()
    {
        @Override
        public void handleMessage(Message message) {
            switch (message.what) {
                case MessageType.READY_FOR_DATA: {
                    log("ready for data");

                    break;
                }

                case MessageType.COULD_NOT_CONNECT: {
                    log("could not connect");

                    break;
                }

                case MessageType.SENDING_DATA: {
                    log("sending data");

                    break;
                }

                case MessageType.DATA_SENT_OK: {
                    log("data sent ok");

                    break;
                }

                case MessageType.DATA_RECEIVED: {
                    log("data received");

                    if (message.obj instanceof File) {

                        File fileMedia = (File) message.obj;
                        String mediaType = message.getData().getString("type");
                        String mediaName = message.getData().getString("name");

                        log ("");
                        addMedia(fileMedia, mediaName, mediaType);

                        return;
                    }

                    break;
                }

                case MessageType.DATA_PROGRESS_UPDATE: {
                    log("data progress update");

                    break;
                }

                case MessageType.DIGEST_DID_NOT_MATCH: {
                    log("digest did not match");

                    break;
                }
            }

            if (message.obj != null) {
                if (message.obj instanceof byte[])
                    log(new String((byte[])message.obj));
                else if (message.obj instanceof ProgressData)
                {
                    ProgressData pd = (ProgressData)message.obj;

                    long remaining = pd.totalSize-pd.remainingSize;



                    int perComplete = -1;

                    perComplete = (int) ((((float) remaining) / ((float) pd.totalSize)) * 100f);
                    log("progress: " + (pd.totalSize - pd.remainingSize) + "/" + pd.totalSize);

                    mProgress.setProgress(perComplete);
                }
                else
                    log(message.obj.toString());
            }
        }
    };

    private void addMedia (final File fileMedia, final String mediaName, final String mimeType)
    {
        Media media = new Media(NearbyActivity.this, fileMedia.getAbsolutePath(), mimeType);
        media.setTitle(mediaName);
        media.save();

        Snackbar snackbar = Snackbar
                .make(findViewById(R.id.main_nearby), "New file received: " + fileMedia.getName(), Snackbar.LENGTH_LONG);

        snackbar.setAction("Open", new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                Intent intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.fromFile(fileMedia),mimeType);
                startActivity(intent);
            }
        });

        snackbar.show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nearby);

        mTvNearbyLog = (TextView)findViewById(R.id.tvnearbylog);
        mViewNearbyDevices = (LinearLayout)findViewById(R.id.nearbydevices);
        mSwitchPairedOnly = (SwitchCompat)findViewById(R.id.tbPairedDevices);

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        mPairedDevicesOnly = prefs.getBoolean("pairedonly",false);
        mSwitchPairedOnly.setChecked(mPairedDevicesOnly);

        mSwitchPairedOnly.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mPairedDevicesOnly = isChecked;

                prefs.edit().putBoolean("pairedonly",mPairedDevicesOnly).commit();

                restartNearby();


            }
        });

        mProgress = (DonutProgress)findViewById(R.id.donut_progress);
        mProgress.setMax(100);

        Button btn = (Button)findViewById(R.id.btnCancel);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
                cancelNearby();

            }
        });

        EventBus.getDefault().register(this);

        mIsServer = getIntent().getBooleanExtra("isServer",false);

        if (mIsServer) {
            startServer();
            mProgress.setInnerBottomText("Sharing >>");
        }
        else {
            startClient();
            mProgress.setInnerBottomText(">> Receiving");
        }

    }

    private void restartNearby ()
    {

        new Thread ()
        {
            public void run ()
            {
                if (serverThread != null)
                    serverThread.cancel();

                for (ClientThread clientThread : clientThreads.values()) {

                    if (clientThread != null && clientThread.isAlive())
                        clientThread.cancel();

                }

                if (mIsServer) {

                    disconnectServer();

                }
                else
                    disconnectClient();

                clientThreads.clear();

                //now start again
                if (mIsServer)
                    startServer();
                else
                    startClient();

            }
        }.start();
    }

    private void cancelNearby ()
    {
        new Thread ()
        {
            public void run ()
            {
                if (serverThread != null)
                    serverThread.cancel();


                for (ClientThread clientThread : clientThreads.values()) {

                    if (clientThread != null && clientThread.isAlive())
                        clientThread.cancel();
                }

                clientThreads.clear();

                if (mIsServer) {

                    disconnectServer();

                }
                else
                    disconnectClient();
            }
        }.start();

    }

    private void log (String msg)
    {
        if (mTvNearbyLog != null)
            mTvNearbyLog.setText(msg);

        Log.d("Nearby",msg);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);

        cancelNearby();
    }

    private void startServer () {

        if (!mBluetoothManager.getAdapter().isEnabled())
        {
            Toast.makeText(this,"You must enable Bluetooth for this sharing to work",Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        long currentMediaId = getIntent().getLongExtra(Globals.EXTRA_CURRENT_MEDIA_ID, -1);

        if (currentMediaId >= 0)
            mMedia = Media.findById(Media.class, currentMediaId);

        boolean isDiscoverable = !mPairedDevicesOnly;

        if (isDiscoverable) {
            if (!mBluetoothManager.isDiscoverable()) {
                setTimeDiscoverable(BluetoothManager.BLUETOOTH_TIME_DICOVERY_3600_SEC);
            }
        }

        selectServerMode(isDiscoverable);

        serverThread = new ServerThread(mBluetoothManager.getAdapter(),mHandler,mPairedDevicesOnly);
        sendMediaFile();
        serverThread.start();

        boolean foundPairedDevice = false;

        //first check for paired devices
        for (BluetoothDevice device: mBluetoothManager.getAdapter().getBondedDevices())
        {
            if (device != null && device.getName() != null &&
                    (device.getBluetoothClass().getDeviceClass() == BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA ||
                            device.getBluetoothClass().getDeviceClass() == BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA ||
                            device.getBluetoothClass().getDeviceClass() == BluetoothClass.Device.PHONE_SMART)) {
                foundPairedDevice = true;
            }
        }

        if (mPairedDevicesOnly && (!foundPairedDevice))
            noPairedDevices(); //no paired device? Prompt user to add!



    }

    private void sendMediaFile ()
    {
        try
        {
            File fileMedia = new File(mMedia.getOriginalFilePath());

            InputStream is = new FileInputStream(fileMedia);
            byte[] digest = Utils.calculateMD5(fileMedia);
            String title = mMedia.getTitle();
            if (TextUtils.isEmpty(title))
                title = fileMedia.getName();

            serverThread.setShareMedia(fileMedia,(int)fileMedia.length(),digest,title,mMedia.getMimeType());

        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private void startClient ()
    {
        selectClientMode();

        boolean foundPairedDevice = false;

        //first check for paired devices
        for (BluetoothDevice device: mBluetoothManager.getAdapter().getBondedDevices())
        {
            if (device != null && device.getName() != null &&
                    (device.getBluetoothClass().getDeviceClass() == BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA ||
                            device.getBluetoothClass().getDeviceClass() == BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA ||
                            device.getBluetoothClass().getDeviceClass() == BluetoothClass.Device.PHONE_SMART)) {

                mFoundDevices.put(device.getAddress(), device);
                //for previously found devices, try to automatically connect
                ClientThread clientThread = new ClientThread(device, mHandler, mPairedDevicesOnly);
                clientThreads.put(device.getAddress(), clientThread);

                clientThread.start();

                foundPairedDevice = true;
            }
        }

        if (mPairedDevicesOnly && (!foundPairedDevice))
            noPairedDevices(); //no paired device? Prompt user to add!

        if (!mPairedDevicesOnly) {

            //start scanning
            scanAllBluetoothDevice();

            //connecting to previously connected devices
            if (clientThreads.isEmpty()) {
                for (BluetoothDevice device : mFoundDevices.values()) {

                    if (!clientThreads.containsKey(device.getAddress())) {
                        //for previously found devices, try to automatically connect
                        ClientThread clientThread = new ClientThread(device, mHandler, mPairedDevicesOnly);
                        clientThread.start();

                        clientThreads.put(device.getAddress(), clientThread);
                    }
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

    }

    @Override
    protected void onPause() {
        super.onPause();


    }

    @Override
    public int myNbrClientMax() {
        return 6;
    }

    @Override
    public void onBluetoothDeviceFound(BluetoothDevice device) {

        log("Found device: " + device.getName());

    }

    @Override
    public void onClientConnectionSuccess() {
        log("client connected");
        sendMessage("Helllooooo client!");

    }

    @Override
    public void onClientConnectionFail() {
        log("client failed");

    }

    @Override
    public void onServeurConnectionSuccess() {
        log("server connected");
        sendMessage("Helllooooo server!");

    }

    @Override
    public void onServeurConnectionFail() {
        log("server failed");

    }

    @Override
    public void onBluetoothStartDiscovery() {
        log("Local BT Address: " + mBluetoothManager.getLocalMacAddress());
        log("bluetooth start discovery");

    }

    @Override
    public void onBluetoothCommunicator(String messageReceive) {
        log("BT communication: " + messageReceive);

    }

    @Override
    public void onBluetoothNotAviable() {
        log("BT failed");

    }

    public void onEventMainThread(BluetoothDevice device) {

        if (device != null && device.getName() != null &&
                (device.getBluetoothClass().getDeviceClass() == BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA ||
                        device.getBluetoothClass().getDeviceClass() == BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA ||
                        device.getBluetoothClass().getDeviceClass() == BluetoothClass.Device.PHONE_SMART)) {


            if (mPairedDevicesOnly && device.getBondState() == BluetoothDevice.BOND_NONE)
                return; //we can only support paired devices


            if (!mFoundDevices.containsKey(device.getAddress())) {
                mFoundDevices.put(device.getAddress(), device);
                addDeviceToView(device);

            }

            if (clientThreads.containsKey(device.getAddress()))
                if (clientThreads.get(device.getAddress()).isAlive())
                    return; //we have a running thread here people!

            log("Found device: " + device.getName() + ":" + device.getAddress());

            ClientThread clientThread = new ClientThread(device, mHandler, mPairedDevicesOnly);
            clientThread.start();

            clientThreads.put(device.getAddress(), clientThread);

        }




    }

    private void addDeviceToView (BluetoothDevice device)
    {

        LinearLayout.LayoutParams imParams =
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        LinearLayout.LayoutParams imgvwDimens = new LinearLayout.LayoutParams(60, 60);
        ImageView iv = new ImageView(this);
        iv.setLayoutParams(imgvwDimens);

        TextDrawable drawable = TextDrawable.builder()
                .buildRoundRect(device.getName().substring(0,1), Color.GREEN, 10);

        iv.setImageDrawable(drawable);

        mViewNearbyDevices.addView(iv,imParams);

    }

    private void noPairedDevices ()
    {
        Snackbar snackbar = Snackbar
                .make(findViewById(R.id.main_nearby), "You have no paired devices. Add now?", Snackbar.LENGTH_LONG);

        snackbar.setAction("Add", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
            openBluetoothSettings();
            }
        });

        snackbar.show();
    }
    private void openBluetoothSettings ()
    {
        Intent intentOpenBluetoothSettings = new Intent();
        intentOpenBluetoothSettings.setAction(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);
        startActivity(intentOpenBluetoothSettings);
    }


}
