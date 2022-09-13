# 实时翻译使用说明
## 前提条件
1. google speech-to-text的服务账户凭证
2. google translate的api key

## 配置凭证
### 配置speech(google speech-to-text)凭证
* 复制speech凭证内容到app/src/main/res/raw/credential.json中（也可以复制凭证文件到app/src/main/res/raw/下，在重命名文件名为credential.json

### 配置translate api key
* 替换app/src/main/java/com/example/clonespeech/Setting.java中GOOGLE_TRANSLATE_KEY中内容
```
package com.example.clonespeech;

public class Setting {
    public static String translateTargetCode = "zh";
    public static String translateSourceCode = "zh";
    public static String inputSourceCode = "zh";

    public static String GOOGLE_TRANSLATE_KEY = "替换的内容";
}

```

### 打包和部署