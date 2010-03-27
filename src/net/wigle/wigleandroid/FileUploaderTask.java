package net.wigle.wigleandroid;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Handler;
import android.os.Message;

public class FileUploaderTask extends Thread {
  private final Context context;
  private final Handler handler;
  private final DatabaseHelper dbHelper;
  private final ProgressDialog pd;
  
  private static final int WRITING_PERCENT_START = 10000;
  private static final String COMMA = ",";
  private static final byte[] COMMA_BYTES;
  private static final byte[] NEWLINE_BYTES;
  
  static {
    byte[] commaBytes = null;
    byte[] newlineBytes = null;
    try {
      commaBytes = COMMA.getBytes( WigleAndroid.ENCODING );
      newlineBytes = "\n".getBytes( WigleAndroid.ENCODING );
    }
    catch ( UnsupportedEncodingException ex ) {
      WigleAndroid.error( "encoding exception: " + ex );
    }
    finally {
      COMMA_BYTES = commaBytes;
      NEWLINE_BYTES = newlineBytes;
    }
  }  
  
  private enum Status {
    UNKNOWN("Unknown", "Unknown error"),
    FAIL( "Fail", "Fail" ),
    SUCCESS( "Success", "Upload Successful"),
    BAD_USERNAME("Fail", "Username not set"),
    BAD_PASSWORD("Fail", "Password not set and username not 'anonymous'"),
    EXCEPTION("Fail", "Exception"),
    BAD_LOGIN("Fail", "Login failed, check password?"),
    UPLOADING("Working...", "Uploading File"),
    WRITING("Working...", "Writing File ");
    
    private final String title;
    private final String message;
    private Status( String title, String message ) {
      this.title = title;
      this.message = message;
    }
    public String getTitle() {
      return title;
    }
    public String getMessage() {
      return message;
    }
  }
  
  public FileUploaderTask( Context context, DatabaseHelper dbHelper ) {
    this.context = context;
    this.dbHelper = dbHelper;
    
    this.pd = ProgressDialog.show( context, Status.WRITING.title, Status.WRITING.getMessage(), true, false );  
    
    this.handler = new Handler() {
      @Override
      public void handleMessage(Message msg) {
        if ( msg.what >= WRITING_PERCENT_START ) {
          int percent = msg.what - WRITING_PERCENT_START;
          pd.setMessage( Status.WRITING.getMessage() + percent + "%" );
          return;
        }
        
        Status status = Status.values()[ msg.what ];
        if ( Status.UPLOADING.equals( status ) ) {
          pd.setMessage( status.getMessage() );
          return;
        }
        pd.dismiss();
        AlertDialog.Builder builder = new AlertDialog.Builder( FileUploaderTask.this.context );
        builder.setCancelable( false );
        builder.setTitle( status.getTitle() );
        builder.setMessage( status.getMessage() );
        AlertDialog ad = builder.create();
        ad.setButton("OK", new DialogInterface.OnClickListener() {
          public void onClick(DialogInterface dialog, int which) {
            dialog.dismiss();
            return;
          } }); 
        ad.show();
      }
     };
  }
  
  public void run() {
    SharedPreferences prefs = context.getSharedPreferences( WigleAndroid.SHARED_PREFS, 0);
    String username = prefs.getString( WigleAndroid.PREF_USERNAME, "" );
    String password = prefs.getString( WigleAndroid.PREF_PASSWORD, "" );
    Status status = Status.UNKNOWN;
    
    if ( "".equals( username ) ) {
      // TODO: error
      WigleAndroid.error( "username not defined" );
      status = Status.BAD_USERNAME;
    }
    else if ( "".equals( password ) && ! WigleAndroid.ANONYMOUS.equals( username.toLowerCase() ) ) {
      // TODO: error
      WigleAndroid.error( "password not defined and username isn't 'anonymous'" );
      status = Status.BAD_PASSWORD;
    }
    else {
      status = doUpload( username, password );
    }

    // tell the gui thread
    handler.sendEmptyMessage( status.ordinal() );
  }
  
  private Status doUpload( String username, String password ) {    
    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    
    SimpleDateFormat fileDateFormat = new SimpleDateFormat("yyyyMMddHHmmss");
    String filename = "WigleWifi_" + fileDateFormat.format(new Date()) + ".csv.gz";
    String filepath = "/sdcard/wiglewifi/";
    
    Status status = Status.UNKNOWN;
    
    try {
      File sdcard = new File( "/sdcard/" );
      boolean hasSD = sdcard.exists() && sdcard.isDirectory();
      String openString = filename;
      if ( hasSD ) {
        File path = new File( filepath );
        path.mkdirs();
        openString = filepath + filename;
      }
      File file = new File( openString );
      if ( ! file.exists() ) {
        file.createNewFile();
      }
      
      FileOutputStream rawFos = hasSD ? new FileOutputStream( file )
        : context.openFileOutput( filename, Context.MODE_WORLD_READABLE );
      GZIPOutputStream fos = new GZIPOutputStream( rawFos );
      // name, version
      writeFos( fos, "WigleWifi-1.0\n" );
      // header
      writeFos( fos, "MAC,SSID,AuthMode,FirstSeen,Channel,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters\n" );
      // write file
      long maxId = dbHelper.getLastUpload();
      Cursor cursor = dbHelper.networkIterator( maxId );
      int lineCount = 0;
      int total = cursor.getCount();
      if ( total > 0 ) {
        int lastSentPercent = 0;
        for ( cursor.moveToFirst(); ! cursor.isAfterLast(); cursor.moveToNext() ) {
          lineCount++;
          
          // _id,bssid,level,lat,lon,time
          long id = cursor.getLong(0);
          if ( id > maxId ) {
            maxId = id;
          }
          String bssid = cursor.getString(1);
          Network network = dbHelper.getNetwork( bssid );
          String ssid = network.getSsid();
          if ( ssid.indexOf( COMMA ) >= 0 ) {
            // comma isn't a legal ssid character, but just in case
            ssid = ssid.replaceAll( COMMA, "_" ); 
          }
          // WigleAndroid.debug("writing network: " + ssid );
          
          writeFos( fos, network.getBssid() );
          fos.write( COMMA_BYTES );
          writeFos( fos, ssid );
          fos.write( COMMA_BYTES );
          writeFos( fos, network.getCapabilities() );
          fos.write( COMMA_BYTES );
          writeFos( fos, dateFormat.format( new Date( cursor.getLong(7) ) ) );
          fos.write( COMMA_BYTES );
          writeFos( fos, Integer.toString( network.getChannel() ) );
          fos.write( COMMA_BYTES );
          writeFos( fos, Integer.toString( cursor.getInt(2) ) );
          fos.write( COMMA_BYTES );
          writeFos( fos, Double.toString( cursor.getDouble(3) ) );
          fos.write( COMMA_BYTES );
          writeFos( fos, Double.toString( cursor.getDouble(4) ) );
          fos.write( COMMA_BYTES );
          writeFos( fos, Double.toString( cursor.getDouble(5) ) );
          fos.write( COMMA_BYTES );
          writeFos( fos, Double.toString( cursor.getDouble(6) ) );
          fos.write( NEWLINE_BYTES );
          
          // update UI
          int percentDone = (lineCount * 100) / total;
          // only send up to 100 times
          if ( percentDone > lastSentPercent ) {
            handler.sendEmptyMessage( WRITING_PERCENT_START + percentDone );
            lastSentPercent = percentDone;
          }
        }
      }
      cursor.close();
      fos.close();
      
      // show on the UI
      handler.sendEmptyMessage( Status.UPLOADING.ordinal() );
      
      // send file
      FileInputStream fis = hasSD ? new FileInputStream( file ) 
        : context.openFileInput( filepath );
      Map<String,String> params = new HashMap<String,String>();
      
      params.put("observer", username);
      params.put("password", password);
      String response = HttpFileUploader.upload( WigleAndroid.FILE_POST_URL, filename, "stumblefile", fis, params );
      
      if ( response.indexOf("uploaded successfully") > 0 ) {
        status = Status.SUCCESS;
        dbHelper.lastUpload( maxId );
      }
      else if ( response.indexOf("does not match login") > 0 ) {
        status = Status.BAD_LOGIN;
      }
      else {
        WigleAndroid.error("fail: " + response );
        status = Status.FAIL;
      }
    } 
    catch (FileNotFoundException e) {
      // TODO Auto-generated catch block
      e.printStackTrace();
      WigleAndroid.error( "file problem: " + e );
      status = Status.EXCEPTION;
    }
    catch ( IOException ex ) {
      ex.printStackTrace();
      WigleAndroid.error( "file problem: " + ex );
      status = Status.EXCEPTION;
    }
    
    return status;
  }
  
  private void writeFos( OutputStream fos, String data ) throws IOException, UnsupportedEncodingException {
    if ( data != null ) {
      fos.write( data.getBytes( WigleAndroid.ENCODING ) );
    }
  }
   
}
