/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.clonespeech;

import android.Manifest;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.res.ResourcesCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.clonespeech.http.bean.GetSupportedLanguagesResponseLanguage;
import com.example.clonespeech.http.bean.LanguageData;
import com.example.clonespeech.http.TranslateV2;
import com.permissionx.guolindev.PermissionX;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import kotlin.text.Regex;


public class MainActivity extends AppCompatActivity {

    private static final String FRAGMENT_MESSAGE_DIALOG = "message_dialog";

    private static final String STATE_RESULTS = "results";

    private static final int REQUEST_RECORD_AUDIO_PERMISSION = 1;

    private VoiceTranslateService mVoiceTranslateService;

    private VoiceHelper mVoiceHelper;
    private final VoiceHelper.Callback mVoiceCallback = new VoiceHelper.Callback() {

        @Override
        public void onVoiceStart() {
            if (mVoiceTranslateService != null) {
                mVoiceTranslateService.startRecognizing(mVoiceHelper.getSampleRate());
            }
        }

        @Override
        public void onVoice(byte[] data, int size) {
            if (mVoiceTranslateService != null) {
                mVoiceTranslateService.recognize(data, size);
            }
        }

        @Override
        public void onVoiceEnd() {
            if (mVoiceTranslateService != null) {
                mVoiceTranslateService.finishRecognizing();
            }
        }

    };

    // Resource caches
    private int mColorHearing;
    private int mColorNotHearing;

    // View references
    private TextView mStatus;
    private TextView mTvOrigin;
    private TextView mTvTranslate;
    private ResultAdapter mAdapter;
    private RecyclerView mRecyclerView;
    private TextView mTvTranslateLanguageSelect;
    private TextView mTvInputLanguageSelect;
    private AlertDialog mTranslateLanguageSelectDialog;

    //翻译语言列表
    private final ArrayList<String> translateLanguageNames = new ArrayList();
    private final ArrayList<String> translateTargetCodes = new ArrayList();
    //语音语言列表
    private final List<String> inputLanguageNames = new ArrayList();
    private final List<String> inputLanguageCodes = new ArrayList();
    private final List<String> translateSourceCodes = new ArrayList();

    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder binder) {
            mVoiceTranslateService = VoiceTranslateService.from(binder);
            mVoiceTranslateService.addListener(mVoiceListener);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mVoiceTranslateService = null;
        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        final Resources resources = getResources();
        final Resources.Theme theme = getTheme();
        mColorHearing = ResourcesCompat.getColor(resources, R.color.status_hearing, theme);
        mColorNotHearing = ResourcesCompat.getColor(resources, R.color.status_not_hearing, theme);

        setSupportActionBar((Toolbar) findViewById(R.id.toolbar));
        mStatus = findViewById(R.id.status);
        mTvOrigin = findViewById(R.id.tv_origin);
        mTvTranslate = findViewById(R.id.tv_translate);

        mRecyclerView = (RecyclerView) findViewById(R.id.recycler_view);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        final ArrayList<String> results = savedInstanceState == null ? null :
                savedInstanceState.getStringArrayList(STATE_RESULTS);
        mAdapter = new ResultAdapter(results);
        mRecyclerView.setAdapter(mAdapter);

        mTvTranslateLanguageSelect = findViewById(R.id.tv_translate_language_select);
        mTvTranslateLanguageSelect.setOnClickListener(v -> {
            if (null != mTranslateLanguageSelectDialog) {
                mTranslateLanguageSelectDialog.show();
            }
        });

        initTranslateCode();
        mTvInputLanguageSelect = findViewById(R.id.tv_input_language_select);
        mTvInputLanguageSelect.setOnClickListener(v -> {
            createInputLanguagesDialog();
        });


        ImageView ivVoice = findViewById(R.id.iv_voice);
        ivVoice.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    showStatus(true);
                    mTvOrigin.setText(null);
                    mTvTranslate.setText(null);
                    startVoiceRecorder();
                    break;
                case MotionEvent.ACTION_UP:
                    showStatus(false);
                    stopVoiceRecorder();
                    break;
            }
            return false;
        });


        HashMap<String, String> map = new HashMap();
        map.put("target", "zh");
        map.put("key", Setting.GOOGLE_TRANSLATE_KEY);
        TranslateV2.getInstance().getLanguages(map).observeOn(AndroidSchedulers.mainThread()).subscribe(new Observer<LanguageData>() {

            @Override
            public void onSubscribe(Disposable d) {

            }

            @Override
            public void onNext(LanguageData languageData) {
                for (GetSupportedLanguagesResponseLanguage languagesResponseLanguage : languageData.data.languages) {
                    if (translateLanguageNames.contains(languagesResponseLanguage.name))
                        continue;
                    translateLanguageNames.add(languagesResponseLanguage.name);
                    translateTargetCodes.add(languagesResponseLanguage.language);
                }
                createTranslateLanguagesDialog();
            }

            @Override
            public void onError(Throwable e) {

            }

            @Override
            public void onComplete() {

            }
        });
    }

    private void initTranslateCode() {
        String[] countryList = this.getResources().getStringArray(R.array.language);
        for (String str : countryList) {
            String[] strList = str.split("\\*");
            inputLanguageNames.add(strList[0].trim());
            inputLanguageCodes.add(strList[1].trim());
            translateSourceCodes.add(strList[2].trim());
        }
    }

    /**
     * 翻译语言选择弹窗
     */
    private void createTranslateLanguagesDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择语言");
        builder.setItems(translateLanguageNames.toArray(new CharSequence[translateLanguageNames.size()]), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mTvTranslateLanguageSelect.setText(translateLanguageNames.get(which));
                Setting.translateTargetCode = translateTargetCodes.get(which);
                dialog.dismiss();
            }
        });
        mTranslateLanguageSelectDialog = builder.create();
    }

    /**
     * 语音输入语言选择弹窗
     */
    private void createInputLanguagesDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("选择语言");
        builder.setItems(inputLanguageNames.toArray(new CharSequence[inputLanguageNames.size()]), (dialog, which) -> {
            mTvInputLanguageSelect.setText(inputLanguageNames.get(which));
            Setting.translateSourceCode = translateSourceCodes.get(which);
            Setting.inputSourceCode = inputLanguageCodes.get(which);
            dialog.dismiss();
        });
        builder.create().show();
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, VoiceTranslateService.class), mServiceConnection, BIND_AUTO_CREATE);
        PermissionX.init(this).permissions(Manifest.permission.RECORD_AUDIO)
                .request((allGranted, grantedList, deniedList) -> {
                });
    }

    @Override
    protected void onStop() {
        mVoiceTranslateService.removeListener(mVoiceListener);
        unbindService(mServiceConnection);
        mVoiceTranslateService = null;
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mAdapter != null) {
            outState.putStringArrayList(STATE_RESULTS, mAdapter.getResults());
        }
    }

    private void startVoiceRecorder() {
        if (mVoiceHelper != null) {
            mVoiceHelper.stop();
        }
        mVoiceHelper = new VoiceHelper(mVoiceCallback);
        mVoiceHelper.start();
    }

    private void stopVoiceRecorder() {
        if (mVoiceHelper != null) {
            mVoiceHelper.stop();
            mVoiceHelper = null;
        }
    }

    private void showStatus(final boolean hearingVoice) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mStatus.setVisibility(hearingVoice ? View.VISIBLE : View.INVISIBLE);
            }
        });
    }

    private final VoiceListener mVoiceListener =
            new VoiceListener() {
                @Override
                public void onVoiceRecognized(final String originStr, final String translateStr, final boolean isFinal) {
                    if (isFinal) {
                        if (mVoiceHelper != null)
                            mVoiceHelper.dismiss();
                    }
                    runOnUiThread(() -> {
                        if (mTvOrigin != null && !TextUtils.isEmpty(originStr)) {
                            mTvOrigin.setText(originStr);
                            mTvTranslate.setText(translateStr);
                            if (isFinal) {
                                mAdapter.addResult(originStr, translateStr);
                                mRecyclerView.smoothScrollToPosition(0);
                            } else {
                                mTvOrigin.setText(originStr);
                                mTvTranslate.setText(translateStr);
                            }
                        }
                    });
                }
            };

    private static class ViewHolder extends RecyclerView.ViewHolder {

        TextView tvItemOrigin;
        TextView tvItemTranslate;

        ViewHolder(LayoutInflater inflater, ViewGroup parent) {
            super(inflater.inflate(R.layout.item_result, parent, false));
            tvItemOrigin = itemView.findViewById(R.id.tv_origin);
            tvItemTranslate = itemView.findViewById(R.id.tv_translate);
        }

    }

    private static class ResultAdapter extends RecyclerView.Adapter<ViewHolder> {

        private final ArrayList<String> originStrList = new ArrayList<>();
        private final ArrayList<String> translateStrList = new ArrayList<>();

        ResultAdapter(ArrayList<String> results) {
            if (results != null) {
                originStrList.addAll(results);
            }
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()), parent);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            holder.tvItemOrigin.setText(originStrList.get(position));
            holder.tvItemTranslate.setText(translateStrList.get(position));
        }

        @Override
        public int getItemCount() {
            return originStrList.size();
        }

        void addResult(String originStr, String translateStr) {
            originStrList.add(0, originStr);
            translateStrList.add(0, translateStr);
            notifyItemInserted(0);
        }

        public ArrayList<String> getResults() {
            return originStrList;
        }

    }

}
