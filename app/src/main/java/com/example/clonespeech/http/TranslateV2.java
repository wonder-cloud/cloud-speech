package com.example.clonespeech.http;

import com.example.clonespeech.http.bean.LanguageData;
import com.example.clonespeech.http.bean.TranslateData;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava2.RxJava2CallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.converter.scalars.ScalarsConverterFactory;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.QueryMap;

public class TranslateV2 {
    public static String GOOGLE_ROOT_PATH = "https://translation.googleapis.com/";
    private static final int CONNECT_TIME = 1;
    private static final int WRITE_TIME = 2;
    private static final int READ_TIME = 2;
    private static final TranslateV2 sInstance = new TranslateV2();
    private final ApiService apiService;

    public static TranslateV2 getInstance(){
        return sInstance;
    }

    private TranslateV2() {
        OkHttpClient googleClient = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIME, TimeUnit.SECONDS)//连接超时时间
                .writeTimeout(WRITE_TIME, TimeUnit.SECONDS)//设置写操作超时时间
                .readTimeout(READ_TIME, TimeUnit.SECONDS)//设置读操作超时时间
                .addInterceptor(new ParamInterceptor()) //添加统一的参数
                .build();
        Retrofit translateRetrofit = new Retrofit.Builder()
                .client(googleClient)
                .baseUrl(GOOGLE_ROOT_PATH)
                .addConverterFactory(ScalarsConverterFactory.create())
                .addConverterFactory(GsonConverterFactory.create())
                .addCallAdapterFactory(RxJava2CallAdapterFactory.createAsync())
                .build();
        apiService = translateRetrofit.create(ApiService.class);
    }

    public Observable<TranslateData> translate(Map<String, String> map) {
        return apiService.translate(map);
    }

    public Observable<LanguageData> getLanguages(Map<String, String> map) {
        return apiService.getLanguages(map);
    }

    public interface ApiService {
        @POST("language/translate/v2")
        Observable<TranslateData> translate(@QueryMap Map<String, String> map);

        @GET("language/translate/v2/languages")
        Observable<LanguageData> getLanguages(@QueryMap Map<String, String> map);
    }

}
