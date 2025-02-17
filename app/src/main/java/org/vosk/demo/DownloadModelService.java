package org.vosk.demo;

import static org.vosk.demo.api.Download.CLEAR;
import static org.vosk.demo.api.Download.COMPLETE;
import static org.vosk.demo.api.Download.UNZIPPING;
import static org.vosk.demo.api.VoskClient.ServiceType.DOWNLOAD_MODEL;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.google.firebase.crashlytics.buildtools.reloc.org.apache.commons.io.IOUtils;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import org.vosk.demo.api.Download;
import org.vosk.demo.api.DownloadProgressListener;
import org.vosk.demo.api.VoskClient;
import org.vosk.demo.api.VoskService;
import org.vosk.demo.ui.model_list.ModelItem;
import org.vosk.demo.ui.model_list.ModelListActivity;
import org.vosk.demo.utils.Error;
import org.vosk.demo.utils.EventBus;
import org.vosk.demo.utils.PreferenceConstants;
import org.vosk.demo.utils.ZipHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;
import okhttp3.ResponseBody;

public class DownloadModelService extends Service {

    public static final File MODEL_FILE_ROOT_PATH = new File(Environment.getExternalStorageDirectory(), "models");
    public static final String DOWNLOAD_MODEL_CHANNEL_ID_VALUE = "download_model_channel_id";
    public static final String DOWNLOAD_MODEL_CHANNEL_NAME = "Vosk model downloader";
    public static final int DOWNLOAD_MODEL_NOTIFICATION_ID = 1;
    public static final int DOWNLOAD_MODEL_MAX_PROGRESS = 100;

    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private final VoskService service = VoskClient.getClient(getListener(), DOWNLOAD_MODEL);
    private SharedPreferences sharedPreferences;
    private final EventBus eventBus = EventBus.getInstance();
    private NotificationManager notificationManager;
    private NotificationCompat.Builder notificationBuilder;

    private int actualProgress = 0;
    private String modelName;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        modelName = sharedPreferences.getString(PreferenceConstants.DOWNLOADING_FILE, "");
        downloadModel(modelName);
        observeEvents();
    }

    private void observeEvents() {
        compositeDisposable.add(eventBus.getDownloadStatusObservable()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(download -> {
                    if (download.getProgress() == UNZIPPING) {

                        File outputFile = new File(MODEL_FILE_ROOT_PATH, modelName + ".zip");
                        File destinationFile = new File(MODEL_FILE_ROOT_PATH, modelName);

                        ZipHelper.unzipFIle(outputFile, destinationFile);
                        actualProgress = CLEAR;
                    } else if (download.getProgress() == COMPLETE) {
                        addOfflineModel();
                        sharedPreferences.edit()
                                .remove(PreferenceConstants.DOWNLOADING_FILE)
                                .apply();
                        if (!sharedPreferences.contains(PreferenceConstants.ACTIVE_MODEL))
                            sharedPreferences.edit().putString(PreferenceConstants.ACTIVE_MODEL, modelName).apply();
                        stopSelf();
                    } else {
                        if (actualProgress != download.getProgress()) {
                            actualProgress = download.getProgress();
                            updateNotificationProgress();
                        }
                    }
                }));
        compositeDisposable.add(EventBus.getInstance().geErrorObservable().subscribeOn(Schedulers.io()).subscribe(error -> stopSelf()));
    }

    private void updateNotificationProgress() {
        notificationBuilder.setProgress(DOWNLOAD_MODEL_MAX_PROGRESS, actualProgress, false);
        notificationManager.notify(DOWNLOAD_MODEL_NOTIFICATION_ID, notificationBuilder.build());
    }

    private void addOfflineModel() {
        String offlineListJson = sharedPreferences.getString(PreferenceConstants.OFFLINE_LIST, "[]");
        Gson gson = new Gson();
        List<ModelItem> offlineModels = gson.fromJson(offlineListJson, new TypeToken<List<ModelItem>>() {
        }.getType());
        offlineModels.add(new ModelItem(modelName));
        String offlineModelsJson = gson.toJson(offlineModels);
        sharedPreferences.edit().putString(PreferenceConstants.OFFLINE_LIST, offlineModelsJson).apply();
    }

    private DownloadProgressListener getListener() {
        return (bytesRead, contentLength, done) -> {
            Download download = new Download();
            download.setTotalFileSize(contentLength);
            download.setCurrentFileSize(bytesRead);
            int progress = (int) ((bytesRead * 100) / contentLength);
            download.setProgress(progress);
            Log.d("DOWNLOAD", "Progress: " + progress);
            EventBus.getInstance().postDownloadStatus(download);
        };
    }

    private void downloadModel(String modelName) {
        File outputFile = new File(MODEL_FILE_ROOT_PATH, modelName + ".zip");
        ZipHelper.createDir(MODEL_FILE_ROOT_PATH);

        compositeDisposable.add(service.downloadFile(outputFile.getName())
                .subscribeOn(Schedulers.io())
                .map(ResponseBody::byteStream)
                .doOnNext(inputStream -> writeFile(inputStream, outputFile))
                .subscribe(inputStream -> EventBus.getInstance().postDownloadStatus(new Download(UNZIPPING, modelName)),
                        error -> EventBus.getInstance().postErrorStatus(Error.CONNECTION)));

    }

    private static void writeFile(InputStream inputStream, File file) {
        try (OutputStream outputStream = new FileOutputStream(file)) {
            IOUtils.copy(inputStream, outputStream);
        } catch (IOException e) {
            EventBus.getInstance().postErrorStatus(Error.WRITE_STORAGE);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        registerNotification();
        return START_NOT_STICKY;
    }

    private void registerNotification() {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Intent notificationIntent = new Intent(this, ModelListActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        notificationBuilder = getNotification(notificationManager, pendingIntent);

        startForeground(DOWNLOAD_MODEL_NOTIFICATION_ID, notificationBuilder.build());
    }

    private NotificationCompat.Builder getNotification(NotificationManager notificationManager, PendingIntent pendingIntent) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(notificationManager, DOWNLOAD_MODEL_CHANNEL_ID_VALUE, DOWNLOAD_MODEL_CHANNEL_ID_VALUE, null);
        }
        return new NotificationCompat.Builder(this, DOWNLOAD_MODEL_CHANNEL_ID_VALUE)
                .setContentTitle(getString(R.string.download_model_service_notification_title))
                .setSmallIcon(R.drawable.icon)
                .setAutoCancel(false)
                .setProgress(DOWNLOAD_MODEL_MAX_PROGRESS, 0, false)
                .setContentIntent(pendingIntent);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    public static void createNotificationChannel(NotificationManager notificationManager, String channelId, String channelName, Uri notificationSoundUri) {
        NotificationChannel notificationChannel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH);
        notificationChannel.setVibrationPattern(new long[]{1000, 1000, 1000, 1000, 1000});

        if (notificationSoundUri != null) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build();
            notificationChannel.setSound(notificationSoundUri, audioAttributes);
        }

        notificationManager.createNotificationChannel(notificationChannel);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        compositeDisposable.clear();
    }
}
