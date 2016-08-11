package com.simonguest.btxfr;

import android.bluetooth.BluetoothSocket;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.webkit.MimeTypeMap;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Date;

class DataTransferThread extends Thread {

    private final String TAG = "btxfr";
    private final BluetoothSocket socket;
    private Handler handler;

    File fileMedia;
    String fileMimeType;
    String fileTitle;
    int dataLength;
    byte[] dataDigest;


    public DataTransferThread(BluetoothSocket socket, Handler handler) {
        this.socket = socket;
        this.handler = handler;
    }

    public void run() {

        if (this.fileMedia != null)
            sendData();
       else
            receiveData();

    }

    public void setData (File fileMedia, int dataLength, byte[] dataDigest, String fileTitle, String mimeType) {

        this.fileMedia = fileMedia;
        this.fileTitle = fileTitle;
        this.dataLength = dataLength;
        this.dataDigest = dataDigest;
        this.fileMimeType = mimeType;
    }

    private void receiveData ()
    {
        try {

            DataInputStream inputStream = new DataInputStream(socket.getInputStream());
            OutputStream outputStream = socket.getOutputStream();
            boolean waitingForHeader = true;

            File dirDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File fileOut = null;
            String fileName = null;
            String fileType = null;
            OutputStream dataOutputStream = null;

            byte[] headerBytes = new byte[22];
            byte[] digest = new byte[16];
            int headerIndex = 0;
            ProgressData progressData = new ProgressData();

            while (true) {
                if (waitingForHeader) {
                   // Log.v(TAG, "Waiting for Header...");
;
                    while (inputStream.available()< headerBytes.length)
                    {
                        try { Thread.sleep(50);} catch (Exception e){}
                    }

                    int bytesRead = inputStream.read(headerBytes);

                    Log.v(TAG, "Received Header Bytes: " + bytesRead);

                    if ((headerBytes[0] == Constants.HEADER_MSB) && (headerBytes[1] == Constants.HEADER_LSB)) {
                        Log.v(TAG, "Header Received.  Now obtaining length");
                        byte[] dataSizeBuffer = Arrays.copyOfRange(headerBytes, 2, 6);
                        progressData.totalSize = Utils.byteArrayToInt(dataSizeBuffer);
                        progressData.remainingSize = progressData.totalSize;
                        Log.v(TAG, "Data size: " + progressData.totalSize);
                        digest = Arrays.copyOfRange(headerBytes, 6, 22);

                        fileName = inputStream.readUTF();
                        fileType = inputStream.readUTF();

                        String fileExt = MimeTypeMap.getSingleton().getExtensionFromMimeType(fileType);

                        fileOut = new File(dirDownloads,new Date().getTime()+"."+fileExt);
                        dataOutputStream = new DataOutputStream(new FileOutputStream(fileOut));

                        waitingForHeader = false;
                        sendProgress(progressData);
                    } else {
                        Log.e(TAG, "Did not receive correct header.  Closing socket");
                        socket.close();
                        handler.sendEmptyMessage(MessageType.INVALID_HEADER);
                        break;
                    }


                }

                if (!waitingForHeader) {
                    // Read the data from the stream in chunks
                    byte[] buffer = new byte[Constants.CHUNK_SIZE];


                    Log.v(TAG, "Waiting for data.  Expecting " + progressData.remainingSize + " more bytes.");

                    while (progressData.remainingSize > 0) {

                        int bytesRead = inputStream.read(buffer);
              //          Log.v(TAG, "Read " + bytesRead + " bytes into buffer");
                        dataOutputStream.write(buffer, 0, bytesRead);
                        progressData.remainingSize -= bytesRead;
                        sendProgress(progressData);
                    }

                    dataOutputStream.flush();
                    dataOutputStream.close();

                    break;
                }
            }

            if (Utils.checkMD5(digest,fileOut)) {
                Log.v(TAG, "Digest matches OK.");
                Message message = new Message();
                message.obj = fileOut;
                message.what = MessageType.DATA_RECEIVED;
                message.getData().putString("name",fileName);
                message.getData().putString("type",fileType);
                handler.sendMessage(message);

                // Send the digest back to the client as a confirmation
                Log.v(TAG, "Sending back digest for confirmation");
                outputStream.write(digest);


            } else {
                Log.e(TAG, "Digest did not match.  Corrupt transfer?");
                handler.sendEmptyMessage(MessageType.DIGEST_DID_NOT_MATCH);
            }

          //  Log.v(TAG, "Closing server socket");
            socket.close();

        } catch (Exception ex) {
            Log.d(TAG, ex.toString());
        }
    }

    private void sendProgress(ProgressData progressData) {
        Message message = new Message();
        message.obj = progressData;
        message.what = MessageType.DATA_PROGRESS_UPDATE;
        handler.sendMessage(message);
    }


    private void sendData ()
    {
        Log.v(TAG, "Handle received data to send");

        try {
            handler.sendEmptyMessage(MessageType.SENDING_DATA);

            InputStream is = new FileInputStream(fileMedia);
            DataOutputStream outputStream = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));

            // Send the header control first
            outputStream.write(Constants.HEADER_MSB);
            outputStream.write(Constants.HEADER_LSB);

            // write size
            outputStream.write(Utils.intToByteArray(dataLength));

            // write digest
            outputStream.write(dataDigest);

            // now write the data
            //outputStream.write(payload);
            //dataOutputStream.write(buffer, 0, bytesRead);
            //progressData.remainingSize -= bytesRead;
            //sendProgress(progressData);
            ProgressData progressData = new ProgressData();
            progressData.totalSize = dataLength;
            progressData.remainingSize = progressData.totalSize;

            outputStream.writeUTF(fileTitle);
            outputStream.writeUTF(fileMimeType);

            Log.v(TAG, "Sending file of " + dataLength + " length");

            byte[] buffer = new byte[Constants.CHUNK_SIZE];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
            //    Log.v(TAG, "Read " + bytesRead + " bytes into buffer; Writing to output...");
                outputStream.write(buffer, 0, bytesRead);
                progressData.remainingSize -= bytesRead;
                sendProgress(progressData);
            }

            outputStream.flush();

            Log.v(TAG, "Data sent.  Waiting for return digest as confirmation");
            DataInputStream inputStream = new DataInputStream(socket.getInputStream());
            byte[] incomingDigest = new byte[16];
            int incomingIndex = 0;

            try {
                while (true) {
                    byte[] header = new byte[1];
                    inputStream.read(header, 0, 1);
                    incomingDigest[incomingIndex++] = header[0];
                    if (incomingIndex == 16) {
                        if (Arrays.equals(dataDigest, incomingDigest)) {
                            Log.d(TAG, "Digest matched OK.  Data was received OK.");
                            handler.sendEmptyMessage(MessageType.DATA_SENT_OK);
                        } else {
                            Log.d(TAG, "Digest did not match.  Might want to resend.");
                            handler.sendEmptyMessage(MessageType.DIGEST_DID_NOT_MATCH);
                        }

                        break;
                    }
                }
            } catch (Exception ex) {
                Log.e(TAG, ex.toString());
            }

        } catch (Exception e) {
            Log.e(TAG, "error reading stream", e);
        }


    }
}
