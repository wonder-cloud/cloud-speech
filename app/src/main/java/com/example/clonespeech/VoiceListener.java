package com.example.clonespeech;

public interface VoiceListener {
    void onVoiceRecognized(String originStr, String translateStr, boolean isFinal);
}
