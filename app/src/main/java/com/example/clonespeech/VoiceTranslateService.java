package com.example.clonespeech;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.clonespeech.http.bean.TranslateData;
import com.example.clonespeech.http.bean.TranslateTextResponseTranslation;
import com.example.clonespeech.http.TranslateV2;
import com.google.auth.Credentials;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.speech.v1.RecognitionAudio;
import com.google.cloud.speech.v1.RecognitionConfig;
import com.google.cloud.speech.v1.RecognizeRequest;
import com.google.cloud.speech.v1.RecognizeResponse;
import com.google.cloud.speech.v1.SpeechGrpc;
import com.google.cloud.speech.v1.SpeechRecognitionAlternative;
import com.google.cloud.speech.v1.SpeechRecognitionResult;
import com.google.cloud.speech.v1.StreamingRecognitionConfig;
import com.google.cloud.speech.v1.StreamingRecognitionResult;
import com.google.cloud.speech.v1.StreamingRecognizeRequest;
import com.google.cloud.speech.v1.StreamingRecognizeResponse;
import com.google.cloud.translate.v3.TranslationServiceGrpc;
import com.google.protobuf.ByteString;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ClientInterceptors;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.internal.DnsNameResolverProvider;
import io.grpc.okhttp.OkHttpChannelProvider;
import io.grpc.stub.StreamObserver;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;


public class VoiceTranslateService extends Service {

    private static final String TAG = "VoiceTranslateService";

    private static final String PREFS = "VoiceTranslateService";
    private static final String PREF_ACCESS_TOKEN_VALUE = "access_token_value";
    private static final String PREF_ACCESS_TOKEN_EXPIRATION_TIME = "access_token_expiration_time";

    /**
     * We reuse an access token if its expiration time is longer than this.
     */
    private static final int ACCESS_TOKEN_EXPIRATION_TOLERANCE = 30 * 60 * 1000; // thirty minutes
    /**
     * We refresh the current access token before it expires.
     */
    private static final int ACCESS_TOKEN_FETCH_MARGIN = 60 * 1000; // one minute

    public static final List<String> SCOPE =
            Collections.singletonList("https://www.googleapis.com/auth/cloud-platform");
    private static final String SPEECH_HOSTNAME = "speech.googleapis.com";
    private static final String TRANSLATE_HOSTNAME = "translate.googleapis.com";
    private static final int PORT = 443;

    private final SpeechBinder mBinder = new SpeechBinder();
    private final ArrayList<VoiceListener> mListeners = new ArrayList<>();
    private volatile AccessTokenTask mAccessTokenTask;
    private SpeechGrpc.SpeechStub mSpeechApi;
    private TranslationServiceGrpc.TranslationServiceStub mTranslateApi;
    private static Handler mHandler;

    /**
     * 音频流语音识别响应回调
     */
    private final StreamObserver<StreamingRecognizeResponse> mStreamRecognizeResponseObserver = new StreamObserver<StreamingRecognizeResponse>() {
        @Override
        public void onNext(StreamingRecognizeResponse response) {
            String text = null;
            boolean isFinal = false;
            if (response.getResultsCount() > 0) {
                final StreamingRecognitionResult result = response.getResults(0);
                isFinal = result.getIsFinal();
                if (result.getAlternativesCount() > 0) {
                    final SpeechRecognitionAlternative alternative = result.getAlternatives(0);
                    text = alternative.getTranscript();

                }
            }
            Log.e(TAG, "mStreamRecognizeResponseObserver onNext:" + text);
            if (text != null) {
                if (isFinal) {
                    startTranslate(text, Setting.translateSourceCode, Setting.translateTargetCode, true);
                }else {
                    for (VoiceListener listener : mListeners) {
                        listener.onVoiceRecognized(text, "", false);
                    }
                }
            }
        }

        @Override
        public void onError(Throwable t) {
            Log.e(TAG, "mStreamRecognizeResponseObserver onError", t);
        }

        @Override
        public void onCompleted() {
            Log.i(TAG, "mStreamRecognizeResponseObserver onCompleted");
        }

    };

    /**
     * 音频文件语音识别响应回调
     */
    private final StreamObserver<RecognizeResponse> mFileRecognizeResponseObserver = new StreamObserver<RecognizeResponse>() {
        @Override
        public void onNext(RecognizeResponse response) {
            String text = null;
            if (response.getResultsCount() > 0) {
                final SpeechRecognitionResult result = response.getResults(0);
                if (result.getAlternativesCount() > 0) {
                    final SpeechRecognitionAlternative alternative = result.getAlternatives(0);
                    text = alternative.getTranscript();
                }
            }
            if (text != null) {
                startTranslate(text, Setting.translateSourceCode, Setting.translateTargetCode, true);
            }
        }

        @Override
        public void onError(Throwable t) {
            Log.e(TAG, "mFileRecognizeResponseObserver onError:", t);
        }

        @Override
        public void onCompleted() {
            Log.i(TAG, "mFileRecognizeResponseObserver onCompleted");
        }

    };

    private StreamObserver<StreamingRecognizeRequest> mStreamRecognizeRequestObserver;

    public static VoiceTranslateService from(IBinder binder) {
        return ((SpeechBinder) binder).getService();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler();
        Log.e(TAG, "onCreate");
        fetchAccessToken();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacks(mFetchAccessTokenRunnable);
        mHandler = null;
        // Release the gRPC channel.
        if (mSpeechApi != null) {
            final ManagedChannel speechApiChannel = (ManagedChannel) mSpeechApi.getChannel();
            
            if (speechApiChannel != null && !speechApiChannel.isShutdown()) {
                try {
                    speechApiChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Log.e(TAG, "speechApiChannel shutting down Error:", e);
                }
            }
            mSpeechApi = null;
        }
        if (mTranslateApi != null) {
            final ManagedChannel translateApiChannel = (ManagedChannel) mTranslateApi.getChannel();
            if (translateApiChannel != null && !translateApiChannel.isShutdown()) {
                try {
                    translateApiChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Log.e(TAG, "translateApiChannel shutting down Error:", e);
                }
            }
            mTranslateApi = null;
        }
    }

    private void fetchAccessToken() {
        if (mAccessTokenTask != null) {
            return;
        }
        mAccessTokenTask = new AccessTokenTask();
        mAccessTokenTask.execute();
    }

    private String getDefaultLanguageCode() {
        final Locale locale = Locale.getDefault();
        final StringBuilder language = new StringBuilder(locale.getLanguage());
        final String country = locale.getCountry();
        if (!TextUtils.isEmpty(country)) {
            language.append("-");
            language.append(country);
        }
        return language.toString();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public void addListener(@NonNull VoiceListener listener) {
        mListeners.add(listener);
    }

    public void removeListener(@NonNull VoiceListener listener) {
        mListeners.remove(listener);
    }


    /**
     * 开始实时音频流识别
     * @param sampleRate
     */
    public void startRecognizing(int sampleRate) {
        if (mSpeechApi == null) {
            return;
        }
        // Configure the API
        mStreamRecognizeRequestObserver = mSpeechApi.streamingRecognize(mStreamRecognizeResponseObserver);
        mStreamRecognizeRequestObserver.onNext(StreamingRecognizeRequest.newBuilder()
                .setStreamingConfig(StreamingRecognitionConfig.newBuilder().setConfig(RecognitionConfig.newBuilder()
                                .setLanguageCode(Setting.inputSourceCode)
                                .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                                .setSampleRateHertz(sampleRate)
                                .build())
                        .setInterimResults(true)
                        .setSingleUtterance(true)
                        .build())
                .build());
    }


    /**
     * 语音识别后进行文字翻译
     * @param content
     * @param sourceCode
     * @param targetCode
     * @param isFinal
     */
    public void startTranslate(String content, String sourceCode, String targetCode, boolean isFinal) {
        if (sourceCode.equals(targetCode)) {
            sourceCode = "";
        }
        HashMap<String, String> map = new HashMap();
        map.put("q", content);
        map.put("source", sourceCode);
        map.put("target", targetCode);
        map.put("key", Setting.GOOGLE_TRANSLATE_KEY);
        TranslateV2.getInstance().translate(map).observeOn(AndroidSchedulers.mainThread()).subscribe(new Observer<TranslateData>() {

            @Override
            public void onSubscribe(Disposable d) {
            }

            @Override
            public void onNext(TranslateData translateData) {
                StringBuffer stringBuffer = new StringBuffer();
                for (TranslateTextResponseTranslation translation : translateData.data.translations) {
                    stringBuffer.append(translation.translatedText);
                }
                for (VoiceListener listener : mListeners) {
                    listener.onVoiceRecognized(content, stringBuffer.toString(), isFinal);
                }
            }

            @Override
            public void onError(Throwable e) {
            }

            @Override
            public void onComplete() {
            }
        });

    }

    /**
     * 持续读取音频流进行语音识别
     * @param data
     * @param size
     */
    public synchronized void recognize(byte[] data, int size) {
        if (mStreamRecognizeRequestObserver == null) {
            return;
        }
        // Call the streaming recognition API
        mStreamRecognizeRequestObserver.onNext(StreamingRecognizeRequest.newBuilder()
                .setAudioContent(ByteString.copyFrom(data, 0, size))
                .build());
    }

    /**
     * 结束语音识别
     */
    public synchronized void finishRecognizing() {
        if (mStreamRecognizeRequestObserver == null) {
            return;
        }
        mStreamRecognizeRequestObserver.onCompleted();
        mStreamRecognizeRequestObserver = null;
    }

    /**
     * 对音频文件进行语音识别
     * @param stream
     */
    public void recognizeInputStream(InputStream stream) {
        try {
            mSpeechApi.recognize(
                    RecognizeRequest.newBuilder()
                            .setConfig(RecognitionConfig.newBuilder()
                                    .setEncoding(RecognitionConfig.AudioEncoding.LINEAR16)
                                    .setLanguageCode("en-US")
                                    .setSampleRateHertz(16000)
                                    .build())
                            .setAudio(RecognitionAudio.newBuilder()
                                    .setContent(ByteString.readFrom(stream))
                                    .build())
                            .build(),
                    mFileRecognizeResponseObserver);
        } catch (IOException e) {
            Log.e(TAG, "recognizeInputStream Error:", e);
        }
    }

    private class SpeechBinder extends Binder {

        VoiceTranslateService getService() {
            return VoiceTranslateService.this;
        }

    }

    private final Runnable mFetchAccessTokenRunnable = new Runnable() {
        @Override
        public void run() {
            fetchAccessToken();
        }
    };

    private class AccessTokenTask extends AsyncTask<Void, Void, AccessToken> {

        @Override
        protected AccessToken doInBackground(Void... voids) {
            final SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String tokenValue = prefs.getString(PREF_ACCESS_TOKEN_VALUE, null);
            long expirationTime = prefs.getLong(PREF_ACCESS_TOKEN_EXPIRATION_TIME, -1);
            // Check if the current token is still valid for a while
            if (tokenValue != null && expirationTime > 0) {
                if (expirationTime
                        > System.currentTimeMillis() + ACCESS_TOKEN_EXPIRATION_TOLERANCE) {
                    return new AccessToken(tokenValue, new Date(expirationTime));
                }
            }

            // ***** WARNING *****
            // In this sample, we load the credential from a JSON file stored in a raw resource
            // folder of this client app. You should never do this in your app. Instead, store
            // the file in your server and obtain an access token from there.
            // *******************
            final InputStream stream = getResources().openRawResource(R.raw.credential);
            try {
                final GoogleCredentials credentials = GoogleCredentials.fromStream(stream)
                        .createScoped(SCOPE);
                final AccessToken token = credentials.refreshAccessToken();
                prefs.edit()
                        .putString(PREF_ACCESS_TOKEN_VALUE, token.getTokenValue())
                        .putLong(PREF_ACCESS_TOKEN_EXPIRATION_TIME,
                                token.getExpirationTime().getTime())
                        .apply();
                return token;
            } catch (IOException e) {
                Log.e(TAG, "Failed to obtain access token.", e);
            }
            return null;
        }

        @Override
        protected void onPostExecute(AccessToken accessToken) {
            mAccessTokenTask = null;
            final ManagedChannel speechChannel = new OkHttpChannelProvider()
                    .builderForAddress(SPEECH_HOSTNAME, PORT)
                    .nameResolverFactory(new DnsNameResolverProvider())
                    .intercept(new GoogleCredentialsInterceptor(new GoogleCredentials(accessToken)
                            .createScoped(SCOPE)))
                    .build();
            mSpeechApi = SpeechGrpc.newStub(speechChannel);
            final ManagedChannel translateChannel = new OkHttpChannelProvider()
                    .builderForAddress(TRANSLATE_HOSTNAME, PORT)
                    .nameResolverFactory(new DnsNameResolverProvider())
                    .intercept(new GoogleCredentialsInterceptor(new GoogleCredentials(accessToken)
                            .createScoped(SCOPE)))
                    .build();
            mTranslateApi = TranslationServiceGrpc.newStub(translateChannel);

            // Schedule access token refresh before it expires
            if (mHandler != null) {
                mHandler.postDelayed(mFetchAccessTokenRunnable,
                        Math.max(accessToken.getExpirationTime().getTime()
                                - System.currentTimeMillis()
                                - ACCESS_TOKEN_FETCH_MARGIN, ACCESS_TOKEN_EXPIRATION_TOLERANCE));
            }
        }
    }

    /**
     * Authenticates the gRPC channel using the specified {@link GoogleCredentials}.
     */
    private static class GoogleCredentialsInterceptor implements ClientInterceptor {

        private final Credentials mCredentials;

        private Metadata mCached;

        private Map<String, List<String>> mLastMetadata;

        GoogleCredentialsInterceptor(Credentials credentials) {
            mCredentials = credentials;
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                final MethodDescriptor<ReqT, RespT> method, CallOptions callOptions,
                final Channel next) {
            return new ClientInterceptors.CheckedForwardingClientCall<ReqT, RespT>(
                    next.newCall(method, callOptions)) {
                @Override
                protected void checkedStart(Listener<RespT> responseListener, Metadata headers)
                        throws StatusException {
                    Metadata cachedSaved;
                    URI uri = serviceUri(next, method);
                    synchronized (this) {
                        Map<String, List<String>> latestMetadata = getRequestMetadata(uri);
                        if (mLastMetadata == null || mLastMetadata != latestMetadata) {
                            mLastMetadata = latestMetadata;
                            mCached = toHeaders(mLastMetadata);
                        }
                        cachedSaved = mCached;
                    }
                    headers.merge(cachedSaved);
                    delegate().start(responseListener, headers);
                }
            };
        }

        /**
         * Generate a JWT-specific service URI. The URI is simply an identifier with enough
         * information for a service to know that the JWT was intended for it. The URI will
         * commonly be verified with a simple string equality check.
         */
        private URI serviceUri(Channel channel, MethodDescriptor<?, ?> method)
                throws StatusException {
            String authority = channel.authority();
            if (authority == null) {
                throw Status.UNAUTHENTICATED
                        .withDescription("Channel has no authority")
                        .asException();
            }
            // Always use HTTPS, by definition.
            final String scheme = "https";
            final int defaultPort = 443;
            String path = "/" + MethodDescriptor.extractFullServiceName(method.getFullMethodName());
            URI uri;
            try {
                uri = new URI(scheme, authority, path, null, null);
            } catch (URISyntaxException e) {
                throw Status.UNAUTHENTICATED
                        .withDescription("Unable to construct service URI for auth")
                        .withCause(e).asException();
            }
            // The default port must not be present. Alternative ports should be present.
            if (uri.getPort() == defaultPort) {
                uri = removePort(uri);
            }
            return uri;
        }

        private URI removePort(URI uri) throws StatusException {
            try {
                return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), -1 /* port */,
                        uri.getPath(), uri.getQuery(), uri.getFragment());
            } catch (URISyntaxException e) {
                throw Status.UNAUTHENTICATED
                        .withDescription("Unable to construct service URI after removing port")
                        .withCause(e).asException();
            }
        }

        private Map<String, List<String>> getRequestMetadata(URI uri) throws StatusException {
            try {
                return mCredentials.getRequestMetadata(uri);
            } catch (IOException e) {
                throw Status.UNAUTHENTICATED.withCause(e).asException();
            }
        }

        private static Metadata toHeaders(Map<String, List<String>> metadata) {
            Metadata headers = new Metadata();
            if (metadata != null) {
                for (String key : metadata.keySet()) {
                    Metadata.Key<String> headerKey = Metadata.Key.of(
                            key, Metadata.ASCII_STRING_MARSHALLER);
                    for (String value : metadata.get(key)) {
                        headers.put(headerKey, value);
                    }
                }
            }
            return headers;
        }

    }

}
