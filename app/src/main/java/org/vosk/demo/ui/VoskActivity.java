// Copyright 2019 Alpha Cephei Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.vosk.demo.ui;

import static org.vosk.demo.DownloadModelService.MODEL_FILE_ROOT_PATH;
import static org.vosk.demo.api.Download.RESTARTING;
import static org.vosk.demo.utils.Error.CONNECTION;
import static org.vosk.demo.utils.Tools.isServiceRunning;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.github.pwittchen.reactivenetwork.library.rx2.ReactiveNetwork;

import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;
import org.vosk.android.SpeechStreamService;
import org.vosk.android.StorageService;
import org.vosk.demo.DownloadModelService;
import org.vosk.demo.R;
import org.vosk.demo.ui.model_list.ModelListActivity;
import org.vosk.demo.utils.Error;
import org.vosk.demo.utils.EventBus;
import org.vosk.demo.utils.PreferenceConstants;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.schedulers.Schedulers;

public class VoskActivity extends Activity implements
        RecognitionListener {

    static private final int STATE_START = 0;
    static private final int STATE_READY = 1;
    static private final int STATE_DONE = 2;
    static private final int STATE_FILE = 3;
    static private final int STATE_MIC = 4;

    public static final int PERMISSIONS_REQUEST_RECORD_AUDIO = 1;
    public static final int PERMISSIONS_REQUEST_ALL_FILES_ACCESS = 2;

    private CompositeDisposable compositeDisposable;
    private SharedPreferences sharedPreferences;

    private Model model;
    private SpeechService speechService;
    private SpeechStreamService speechStreamService;
    private TextView resultView;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.main);

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        // Setup layout
        resultView = findViewById(R.id.result_text);
        setUiState(STATE_START);

        findViewById(R.id.navigate_model_lists).setOnClickListener(view -> {
            int storagePermissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
            if (storagePermissionCheck != PackageManager.PERMISSION_GRANTED)
                navigateToModelList();
            else
                requestAllFilesAccessPermission();
        });
        findViewById(R.id.recognize_file).setOnClickListener(view -> recognizeFile());
        findViewById(R.id.recognize_mic).setOnClickListener(view -> recognizeMicrophone());
        ((ToggleButton) findViewById(R.id.pause)).setOnCheckedChangeListener((view, isChecked) -> pause(isChecked));

        LibVosk.setLogLevel(LogLevel.INFO);
    }

    private void observeEvents() {
        compositeDisposable.add(EventBus.getInstance().getConnectionEvent().subscribe(state -> {
                    if (state == NetworkInfo.State.CONNECTED) {
                        checkIfIsDownloading();
                    }
                })
        );

        compositeDisposable.add(
                EventBus.getInstance().geErrorObservable()
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(this::handleError)
        );

        compositeDisposable.add(ReactiveNetwork.observeNetworkConnectivity(getApplicationContext())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(connectivity -> {
                    if (connectivity.getState() == NetworkInfo.State.CONNECTED || connectivity.getState() == NetworkInfo.State.DISCONNECTED)
                        EventBus.getInstance().postConnectionEvent(connectivity.getState());
                })
        );
    }

    private void handleError(Error error) {
        switch (error) {
            case CONNECTION:
                Toast.makeText(this, getString(R.string.connection_error), Toast.LENGTH_LONG).show();
                break;
            case WRITE_STORAGE:
                Toast.makeText(this, getString(R.string.write_storage_error), Toast.LENGTH_LONG).show();
                break;
        }
    }

    private void checkIfIsDownloading() {
        String modelDownloadingName = sharedPreferences.getString(PreferenceConstants.DOWNLOADING_FILE, "");
        if (!modelDownloadingName.equals("") && !isServiceRunning(this)) {
            ModelListActivity.progress = RESTARTING;
            startDownloadModelService();
        }
    }

    private void startDownloadModelService() {
        if (!isServiceRunning(this)) {
            Intent service = new Intent(this, DownloadModelService.class);
            ContextCompat.startForegroundService(this, service);
        }
    }

    private void navigateToModelList() {
        Intent intent = new Intent(this, ModelListActivity.class);
        startActivity(intent);
    }


    private void initModel() {

        if (sharedPreferences.contains(PreferenceConstants.ACTIVE_MODEL)) {
            File outputFile = new File(MODEL_FILE_ROOT_PATH, sharedPreferences.getString(PreferenceConstants.ACTIVE_MODEL, "") + "/" + sharedPreferences.getString(PreferenceConstants.ACTIVE_MODEL, ""));

            compositeDisposable.add(Single.fromCallable(() -> new Model(outputFile.getAbsolutePath()))
                    .doOnSuccess(model1 -> this.model = model1)
                    .delay(1, TimeUnit.MILLISECONDS)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(model1 -> setUiState(STATE_READY), Throwable::printStackTrace));
        } else
            StorageService.unpack(this, "model-en-us", "model",
                    (model) -> {
                        this.model = model;
                        setUiState(STATE_READY);
                    },
                    (exception) -> setErrorState("Failed to unpack the model" + exception.getMessage()));
    }


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == PERMISSIONS_REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Recognizer initialization is a time-consuming and it involves IO,
                // so we execute it in async task
                initModel();
            } else {
                Toast.makeText(this, getString(R.string.mic_permission_error), Toast.LENGTH_SHORT).show();
                finish();
            }
        }
        if (requestCode == PERMISSIONS_REQUEST_ALL_FILES_ACCESS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                navigateToModelList();
            } else {
                Toast.makeText(this, getString(R.string.file_access_permission_error), Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (speechService != null) {
            speechService.stop();
            speechService.shutdown();
        }

        if (speechStreamService != null) {
            speechStreamService.stop();
        }
        compositeDisposable.clear();
    }

    @Override
    public void onResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
    }

    @Override
    public void onFinalResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
        setUiState(STATE_DONE);
        if (speechStreamService != null) {
            speechStreamService = null;
        }
    }

    @Override
    public void onPartialResult(String hypothesis) {
        resultView.append(hypothesis + "\n");
    }

    @Override
    public void onError(Exception e) {
        setErrorState(e.getMessage());
    }

    @Override
    public void onTimeout() {
        setUiState(STATE_DONE);
    }

    private void setUiState(int state) {
        switch (state) {
            case STATE_START:
                resultView.setText(R.string.preparing);
                resultView.setMovementMethod(new ScrollingMovementMethod());
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_READY:
                resultView.setText(R.string.ready);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_DONE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.recognize_file);
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_FILE:
                ((Button) findViewById(R.id.recognize_file)).setText(R.string.stop_file);
                resultView.setText(getString(R.string.starting));
                findViewById(R.id.recognize_mic).setEnabled(false);
                findViewById(R.id.recognize_file).setEnabled(true);
                findViewById(R.id.pause).setEnabled((false));
                break;
            case STATE_MIC:
                ((Button) findViewById(R.id.recognize_mic)).setText(R.string.stop_microphone);
                resultView.setText(getString(R.string.say_something));
                findViewById(R.id.recognize_file).setEnabled(false);
                findViewById(R.id.recognize_mic).setEnabled(true);
                findViewById(R.id.pause).setEnabled((true));
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + state);
        }
    }

    private void setErrorState(String message) {
        resultView.setText(message);
        ((Button) findViewById(R.id.recognize_mic)).setText(R.string.recognize_microphone);
        findViewById(R.id.recognize_file).setEnabled(false);
        findViewById(R.id.recognize_mic).setEnabled(false);
    }

    private void recognizeFile() {
        if (speechStreamService != null) {
            setUiState(STATE_DONE);
            speechStreamService.stop();
            speechStreamService = null;
        } else {
            setUiState(STATE_FILE);
            try {
                Recognizer rec = new Recognizer(model, 16000.f, "[\"one zero zero zero one\", " +
                        "\"oh zero one two three four five six seven eight nine\", \"[unk]\"]");

                InputStream ais = getAssets().open(
                        "10001-90210-01803.wav");
                if (ais.skip(44) != 44) throw new IOException("File too short");

                speechStreamService = new SpeechStreamService(rec, ais, 16000);
                speechStreamService.start(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        compositeDisposable = new CompositeDisposable();
        observeEvents();
        setUiState(STATE_START);
        checkPermissions();
    }

    private void checkPermissions() {
        int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.RECORD_AUDIO);
        if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSIONS_REQUEST_RECORD_AUDIO);
        } else {
            initModel();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        compositeDisposable.clear();
    }

    private void recognizeMicrophone() {
        if (speechService != null) {
            setUiState(STATE_DONE);
            speechService.stop();
            speechService = null;
        } else {
            setUiState(STATE_MIC);
            try {
                Recognizer rec = new Recognizer(model, 16000.0f);
                speechService = new SpeechService(rec, 16000.0f);
                speechService.startListening(this);
            } catch (IOException e) {
                setErrorState(e.getMessage());
            }
        }
    }


    private void pause(boolean checked) {
        if (speechService != null) {
            speechService.setPause(checked);
        }
    }

    private void requestAllFilesAccessPermission() {
        // Check if user has given all files access permission to record audio, init model after permission is granted
        if (Build.VERSION.SDK_INT >= 30) {
            Log.i(VoskActivity.class.getName(), "API level >= 30");
            if (!Environment.isExternalStorageManager()) {
                // Request permission
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    Uri uri = Uri.fromParts("package", getPackageName(), null);
                    intent.setData(uri);
                    startActivityForResult(intent, PERMISSIONS_REQUEST_ALL_FILES_ACCESS);
                } catch (android.content.ActivityNotFoundException e) {
                    setErrorState("Failed to request all files access permission");
                }
            } else {
                navigateToModelList();
            }
        } else {
            Log.i(VoskActivity.class.getName(), "API level < 30");
            // Request permission
            int permissionCheck = ContextCompat.checkSelfPermission(getApplicationContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE);
            if (permissionCheck != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSIONS_REQUEST_ALL_FILES_ACCESS);
            } else {
                navigateToModelList();
            }
        }
    }
}
