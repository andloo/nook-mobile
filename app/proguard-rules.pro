# R8/ProGuard 规则（release 构建：minifyEnabled + shrinkResources）。

# Gson：TypeToken 依赖泛型签名做反射解析（I18nManager / SettingsRepository 的
# Map、List、String[] 反序列化），必须保留签名与 TypeToken 子类。
-keepattributes Signature
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken